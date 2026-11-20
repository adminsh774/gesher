[CmdletBinding()]
param([Parameter(Mandatory)][string] $Dir)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
$Dir = (Resolve-Path -LiteralPath $Dir).Path
$results = Join-Path $Dir 'results'
New-Item -ItemType Directory -Path $results -ErrorAction Stop | Out-Null

function Execute([string] $Name) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $Dir "$Name.exe"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Start failed: $Name" }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) { throw "Timed out: $Name" }
        $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $results "$Name.log"), $text)
        Write-Host $text
        return [pscustomobject]@{name=$Name;exit=$process.ExitCode;text=$text}
    } finally {
        if ($process.Id -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}

$old = Execute 'old-numeric'
$fixed = Execute 'fixed-numeric'
$clear = Execute 'clear-only-numeric'
if ($old.exit -ne 1 -or $old.text -notmatch '(?m)^NUMERIC_FENV_DONE checks=192 failures=4\r?$' -or
    @([regex]::Matches($old.text, '(?m)^NUMERIC_FENV_FAIL native prior flags isolated host_round=')).Count -ne 4) {
    throw 'Old production scope did not reproduce the four original Windows failures'
}
if ($fixed.exit -ne 0 -or $fixed.text -notmatch '(?m)^NUMERIC_FENV_DONE checks=192 failures=0\r?$' -or
    $fixed.text -notmatch '(?m)^NUMERIC_FENV_RESTORE_DONE checks=384 failures=0\r?$' -or
    $fixed.text -match 'NUMERIC_FENV_(RESTORE_)?FAIL') { throw 'Fixed production scope failed' }
if ($clear.exit -ne 1 -or $clear.text -notmatch '(?m)^NUMERIC_FENV_DONE checks=192 failures=0\r?$' -or
    $clear.text -notmatch '(?m)^NUMERIC_FENV_RESTORE_DONE checks=384 failures=[1-9][0-9]*\r?$' -or
    $clear.text -notmatch 'NUMERIC_FENV_RESTORE_FAIL standard nontrapping SSE environment installed') {
    throw 'Clear-flags-only negative did not expose inherited native control leakage'
}
$unexpectedClear = @($clear.text -split '\r?\n' | Where-Object {
    $_ -match '^NUMERIC_FENV_(RESTORE_)?FAIL' -and
    $_ -notmatch '^NUMERIC_FENV_RESTORE_FAIL standard nontrapping SSE environment installed '
})
if ($unexpectedClear.Count) { throw 'Clear-only negative has an unrelated failure' }

# Execute only the exact existing reader function, not a reimplementation.
$parseErrors = $null; $tokens = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $Dir 'actual-window-reader.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Actual window reader did not parse' }
$functions = @($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'LogText'
}, $true))
if ($functions.Count -ne 1) { throw 'Missing unique actual LogText definition' }
. ([scriptblock]::Create($functions[0].Extent.Text))

function IsSharingViolation($Exception) {
    for ($errorObject = $Exception; $null -ne $errorObject; $errorObject = $errorObject.InnerException) {
        if ($errorObject -is [IO.IOException] -and ($errorObject.HResult -band 65535) -eq 32) { return $true }
    }
    return $false
}

$sharing = @()
$markers = @("PASS`n", "CONTENT_PASS`n", "BACKGROUND_PASS`n")
foreach ($variant in 'old','fixed') {
    foreach ($stage in 0,1,2) {
        $path = Join-Path $results "$variant-marker-$stage.txt"
        $expected = $markers[$stage]
        if ($stage -gt 0) {
            [IO.File]::WriteAllText($path, "PASS`n", [Text.UTF8Encoding]::new($false))
            $expected = "PASS`n" + $expected
        }
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = Join-Path $Dir "$variant-held-writer.exe"
        $start.Arguments = "$stage `"$path`""
        $start.UseShellExecute = $false; $start.CreateNoWindow = $true
        $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
        $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
        try {
            if (-not $process.Start()) { throw 'Held writer failed to start' }
            $line = $process.StandardOutput.ReadLineAsync()
            if (-not $line.Wait(10000) -or $line.GetAwaiter().GetResult() -ne 'WRITER_HELD') { throw 'No actual held writer handshake' }
            if ($process.HasExited) { throw 'Writer closed before concurrent read' }
            $blocked = $false; $text = $null
            try { $text = LogText $path } catch {
                if (-not (IsSharingViolation $_.Exception)) { throw }
                $blocked = $true
            }
            if ($variant -eq 'old') {
                if (-not $blocked) { throw 'Old exclusive writer did not reject the real reader' }
            } elseif ($blocked -or $text -cne $expected) { throw 'Fixed held writer did not permit exact marker bytes' }
            # Read sharing must not grant a second writer access.
            $other = $null; $otherDenied = $false
            try {
                $other = [IO.FileStream]::new($path, [IO.FileMode]::Open, [IO.FileAccess]::Write,
                    [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
            } catch {
                if (-not (IsSharingViolation $_.Exception)) { throw }
                $otherDenied = $true
            } finally { if ($null -ne $other) { $other.Dispose() } }
            if (-not $otherDenied) { throw 'Owned marker writer lost exclusive write access' }
            $process.StandardInput.Write('R'); $process.StandardInput.Flush()
            if (-not $process.WaitForExit(10000) -or $process.ExitCode -ne 0) { throw 'Held writer release failed' }
            $tail = $process.StandardOutput.ReadToEnd()
            $errors = $process.StandardError.ReadToEnd()
            if ($tail.Trim() -ne 'WRITER_CLOSED' -or $errors) { throw 'Unexpected writer completion' }
            if ((LogText $path) -cne $expected) { throw 'Marker bytes changed after closing the writer' }
            $sharing += [pscustomobject]@{variant=$variant;stage=$stage;readerBlocked=$blocked;otherWriterDenied=$otherDenied;exactBytes=$true}
            Write-Host "MARKER_HELD_CONTROL variant=$variant stage=$stage readerBlocked=$blocked otherWriterDenied=$otherDenied exactBytes=true"
        } finally {
            if ($process.Id -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
            $process.Dispose()
        }
    }
}
$report = [pscustomobject]@{
    source=$env:GITHUB_SHA;run=$env:GITHUB_RUN_ID;attempt=$env:GITHUB_RUN_ATTEMPT;runner=$env:RUNNER_OS
    numeric=@($old,$fixed,$clear);sharing=$sharing
    scope='Actual Windows CRT and file-sharing controls; full product ARM/graphics cases remain separate required gates'
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $results 'result.json') -Encoding UTF8
Write-Host 'QA213_NATIVE_DIAGNOSTIC_PASS numericOld=4 numericFixed=0 restorationChecks=384 markerControls=6'
