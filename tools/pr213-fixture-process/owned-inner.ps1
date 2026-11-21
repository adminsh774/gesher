param(
  [Parameter(Mandatory)][ValidateSet('Old', 'Finally')][string] $Mode,
  [Parameter(Mandatory)][ValidateRange(1,3)][int] $Count,
  [Parameter(Mandatory)][string] $Root
)
$ErrorActionPreference = 'Stop'
$owned = [Collections.Generic.List[object]]::new()
$failure = $null
try {
  foreach ($index in 1..$Count) {
    $stop = Join-Path $Root "child-$index.stop"
    $command = "`$deadline = [DateTime]::UtcNow.AddMinutes(3); [Console]::Out.WriteLine('OWNED_CHILD_READY'); while (-not [IO.File]::Exists('$($stop.Replace("'","''"))') -and [DateTime]::UtcNow -lt `$deadline) { Start-Sleep -Milliseconds 100 }; [Console]::Out.WriteLine('OWNED_CHILD_STOPPED')"
    # Match Track50's native process creation and separate redirected output files.
    $process = Start-Process (Join-Path $PSHOME 'pwsh.exe') -PassThru -NoNewWindow `
      -ArgumentList '-NoProfile', '-EncodedCommand', ([Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))) `
      -RedirectStandardOutput (Join-Path $Root "child-$index.out") `
      -RedirectStandardError (Join-Path $Root "child-$index.err")
    $handle = $process.SafeHandle
    $record = [pscustomobject]@{ Process = $process; Handle = $handle; Pid = $process.Id;
      Created = $process.StartTime.ToUniversalTime().ToFileTimeUtc(); Index = $index }
    $owned.Add($record)
    @($owned | ForEach-Object { [pscustomobject]@{ pid=$_.Pid; created=$_.Created; index=$_.Index } }) |
      ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $Root 'owned.json')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $text = ''
    while ($watch.ElapsedMilliseconds -lt 15000 -and -not $process.HasExited) {
      $text = if (Test-Path (Join-Path $Root "child-$index.out")) {
        (Get-Content -LiteralPath (Join-Path $Root "child-$index.out") -Raw -ErrorAction Stop) + ''
      } else { '' }
      if ($text.Contains('OWNED_CHILD_READY')) { break }
      Start-Sleep -Milliseconds 50
    }
    if ($process.HasExited -or -not $text.Contains('OWNED_CHILD_READY')) {
      throw "owned child $index failed readiness"
    }
  }
  [IO.File]::WriteAllText((Join-Path $Root 'primary'), 'PR213_PRIMARY_FAILURE_SENTINEL')
  throw 'PR213_PRIMARY_FAILURE_SENTINEL'
} catch {
  $failure = $_
  throw
} finally {
  if ($Mode -eq 'Finally') {
    $cleanup = @()
    for ($index = $owned.Count - 1; $index -ge 0; $index--) {
      $record = $owned[$index]
      $process = $record.Process
      $problem = ''
      try {
        if (-not $process.HasExited) { $process.Kill($true) }
        if (-not $process.WaitForExit(5000)) { throw 'Owned child did not stop within five seconds' }
      } catch { $problem = [string]$_ }
      finally { $process.Dispose() }
      $cleanup += [pscustomobject]@{ pid=$record.Pid; created=$record.Created;
        closed=$record.Handle.IsClosed; problem=$problem }
    }
    $cleanup | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $Root 'cleanup.json')
    $problems = @($cleanup | Where-Object { $_.problem -or -not $_.closed })
    if ($problems.Count) {
      if ($null -ne $failure) { Write-Error -ErrorAction Continue ('Cleanup failed: ' + ($problems | ConvertTo-Json -Compress)) }
      else { throw 'Owned cleanup failed' }
    }
  }
}
