param(
  [Parameter(Mandatory)][string] $InstallerRoot,
  [Parameter(Mandatory)][string] $FixtureRoot,
  [Parameter(Mandatory)][string] $Output
)
$ErrorActionPreference = 'Stop'
$Output = [IO.Path]::GetFullPath($Output)
$FixtureRoot = [IO.Path]::GetFullPath($FixtureRoot)
New-Item -ItemType Directory -Force $Output | Out-Null
$fixture = [IO.File]::ReadAllText((Join-Path $FixtureRoot 'build-receipt.json')) | ConvertFrom-Json
if ($fixture.source -cne $env:GITHUB_SHA -or [string]$fixture.run -cne $env:GITHUB_RUN_ID -or
    [string]$fixture.attempt -cne $env:GITHUB_RUN_ATTEMPT) { throw 'Fresh fixture producer source/run/attempt mismatch' }
foreach ($entry in $fixture.outputs.PSObject.Properties) {
  $path = Join-Path $FixtureRoot $entry.Name
  if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.Value.sha256 -or
      (Get-Item -LiteralPath $path).Length -ne $entry.Value.bytes) { throw "Fresh fixture payload mismatch: $($entry.Name)" }
}
$setups = @(Get-ChildItem -LiteralPath $InstallerRoot -Filter *.exe -Recurse)
if ($setups.Count -ne 1) { throw 'Exact historical installer archive must have one setup executable' }
$Dir = Join-Path $env:RUNNER_TEMP ('pr213-art-runtime-' + [guid]::NewGuid().ToString('N'))
$installer = Start-Process $setups[0].FullName -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/DIR=`"$Dir`"") -PassThru
$installerHandle = $installer.SafeHandle
try {
  if (-not $installer.WaitForExit(180000)) {
    $installer.Kill($true)
    [void]$installer.WaitForExit(5000)
    throw 'Historical diagnostic installer exceeded three minutes'
  }
  if ($installer.ExitCode -ne 0) { throw "Historical installer failed: $($installer.ExitCode)" }
} finally { $installer.Dispose() }
if (-not $installerHandle.IsClosed) { throw 'Installer process handle remained open' }
$installed = @(Get-ChildItem -LiteralPath $Dir -File -Recurse | ForEach-Object {
  [pscustomobject]@{path=[IO.Path]::GetRelativePath($Dir,$_.FullName);bytes=$_.Length;
    sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$installed | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $Output 'historical-installed-bytes.json')
$Extracted = Join-Path $Output 'track50-exact'
python "$PSScriptRoot/generate_art.py" $Extracted
if ($LASTEXITCODE -ne 0) { throw 'Exact Track50 extraction failed' }
$observations = [Collections.Generic.List[object]]::new()
$failures = [Collections.Generic.List[string]]::new()
function Open-OwnedArtProcess($Record) {
  try { $process = [Diagnostics.Process]::GetProcessById([int]$Record.pid) }
  catch [ArgumentException] { return $null }
  $handle = $process.SafeHandle
  if ($process.StartTime.ToUniversalTime().ToFileTimeUtc() -ne [long]$Record.created) {
    $process.Dispose()
    throw 'ART owned PID was reused; refusing process operation'
  }
  return [pscustomobject]@{Process=$process;Handle=$handle;Role=$Record.role}
}
foreach ($case in 'Unsigned','Signed','Injected') {
  $folder = Join-Path $Output $case
  New-Item -ItemType Directory -Path $folder | Out-Null
  $source = if ($case -eq 'Unsigned') { 'original' } else { 'signed' }
  Copy-Item (Join-Path $FixtureRoot "$source/components.apk") "$Dir/components.apk" -Force
  Copy-Item (Join-Path $FixtureRoot "$source/services-tests.dex") "$Dir/services-tests.dex" -Force
  $psi = [Diagnostics.ProcessStartInfo]::new((Join-Path $PSHOME 'pwsh.exe'))
  foreach ($arg in @('-NoProfile','-File',"$PSScriptRoot/art-inner.ps1",
      '-Dir',$Dir,'-CaseRoot',$folder,'-Extracted',$Extracted,'-Case',$case)) { $psi.ArgumentList.Add($arg) }
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $row = [ordered]@{case=$case;historicalRuntimeSource='9eec2089061fb5eb0586cd59da16d56ed7e1dfd2';
    fixtureSource=if($case -eq 'Unsigned'){'9eec2089061fb5eb0586cd59da16d56ed7e1dfd2'}else{$env:GITHUB_SHA};
    fixtureApkSha256=(Get-FileHash "$Dir/components.apk" -Algorithm SHA256).Hash.ToLowerInvariant();
    servicesDexSha256=(Get-FileHash "$Dir/services-tests.dex" -Algorithm SHA256).Hash.ToLowerInvariant();
    problems=@()}
  $process = $null
  $handle = $null
  $children = @()
  $cancel = [Threading.CancellationTokenSource]::new()
  $out = $null
  $err = $null
  try {
    $process = [Diagnostics.Process]::Start($psi)
    $handle = $process.SafeHandle
    $out = $process.StandardOutput.ReadToEndAsync($cancel.Token)
    $err = $process.StandardError.ReadToEndAsync($cancel.Token)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Path (Join-Path $folder 'completed')) -and -not (Test-Path (Join-Path $folder 'failure')) -and
        -not $process.HasExited -and $watch.ElapsedMilliseconds -lt 600000) {
      Start-Sleep -Milliseconds 100
    }
    $row.markerWaitMs = $watch.ElapsedMilliseconds
    $row.completed = Test-Path (Join-Path $folder 'completed')
    $row.failed = Test-Path (Join-Path $folder 'failure')
    if (-not $row.completed -and -not $row.failed) { $row.problems += 'Actual Track50 prefix did not reach a terminal result within bounded diagnostic time' }
    $row.innerExitedBeforeOwnedCleanup = $process.WaitForExit(15000)
    $row.stdoutCompleteBeforeOwnedCleanup = $out.IsCompleted
    $row.stderrCompleteBeforeOwnedCleanup = $err.IsCompleted
    if (Test-Path (Join-Path $folder 'owned.json')) {
      $records = @([IO.File]::ReadAllText((Join-Path $folder 'owned.json')) | ConvertFrom-Json)
      foreach ($record in $records) {
        $child = Open-OwnedArtProcess $record
        if ($null -ne $child) { $children += $child }
      }
      $row.owned = $records
    } else { $row.problems += 'Missing real native process ownership receipt' }
    $row.liveOwnedBeforeCleanup = @($children | Where-Object { -not $_.Process.HasExited }).Count
    if ($case -eq 'Unsigned') {
      if (-not $row.failed -or -not ([IO.File]::ReadAllText((Join-Path $folder 'failure')).Contains('Cannot persist AppOps without verified signing identity'))) {
        $row.problems += 'Actual original unsigned APK/DEX did not reproduce the original AppOps signing failure'
      }
      if ($row.liveOwnedBeforeCleanup -ne 3) { $row.problems += 'Original early-error control did not retain all three observed real device processes' }
    } else {
      if (-not $row.innerExitedBeforeOwnedCleanup -or $row.liveOwnedBeforeCleanup -ne 0) {
        $row.problems += 'Proposed owned-finally boundary did not retire actual ART device processes'
      }
      if (-not (Test-Path (Join-Path $folder 'cleanup.json'))) { $row.problems += 'No actual ART owned-cleanup receipt' }
      else {
        $row.cleanup = @([IO.File]::ReadAllText((Join-Path $folder 'cleanup.json')) | ConvertFrom-Json)
        if (@($row.cleanup | Where-Object { $_.problem -or -not $_.closed -or -not $_.exited }).Count) {
          $row.problems += 'Actual ART cleanup did not close all owned native handles'
        }
      }
      if ($case -eq 'Signed' -and (-not $row.completed -or $row.failed)) { $row.problems += 'Signed real Settings/lifecycle section failed' }
      if ($case -eq 'Injected' -and (-not $row.failed -or
          -not ([IO.File]::ReadAllText((Join-Path $folder 'failure')).Contains('PR213_AFTER_REAL_SETTINGS_FAILURE')))) {
        $row.problems += 'Post-setup error control lost its exact injected primary failure'
      }
    }
  } catch { $row.problems += [string]$_ }
  finally {
    # If readiness failed before the normal receipt read, still capture only the
    # recorded exact native owners before trying bounded cleanup.
    if (-not $children.Count -and (Test-Path (Join-Path $folder 'owned.json'))) {
      foreach ($record in @([IO.File]::ReadAllText((Join-Path $folder 'owned.json')) | ConvertFrom-Json)) {
        try {
          $child = Open-OwnedArtProcess $record
          if ($null -ne $child) { $children += $child }
        } catch { $row.problems += "Owned ART capture: $_" }
      }
    }
    foreach ($child in $children) {
      try {
        if (-not $child.Process.HasExited) { $child.Process.Kill($true) }
        if (-not $child.Process.WaitForExit(5000)) { $row.problems += 'Outer actual ART cleanup exceeded five seconds' }
      } catch { $row.problems += "Outer actual ART cleanup: $_" }
      finally { $child.Process.Dispose() }
      if (-not $child.Handle.IsClosed) { $row.problems += 'Outer actual ART native handle remained open' }
    }
    if ($null -ne $process) {
      $row.innerExitedAfterOwnedCleanup = $process.WaitForExit(5000)
      if (-not $row.innerExitedAfterOwnedCleanup) {
        $row.problems += 'Inner test remained alive after exact real device retirement'
        $process.Kill($true)
        [void]$process.WaitForExit(5000)
      }
      $row.exitCode = if ($process.HasExited) { $process.ExitCode } else { $null }
      $row.stdoutCompleteAfterOwnedCleanup = $out.Wait(5000)
      $row.stderrCompleteAfterOwnedCleanup = $err.Wait(5000)
      if ($row.stdoutCompleteAfterOwnedCleanup) {
        [IO.File]::WriteAllText((Join-Path $folder 'inner.out'), $out.Result)
        if ($case -ne 'Unsigned' -and -not $out.Result.Contains('FixtureSettingsSetup persisted special access UID')) {
          $row.problems += 'Missing actual successful persistent special-access readback'
        }
      }
      if ($row.stderrCompleteAfterOwnedCleanup) {
        [IO.File]::WriteAllText((Join-Path $folder 'inner.err'), $err.Result)
        $expectedFailure = if ($case -eq 'Unsigned') {'Cannot persist AppOps without verified signing identity'}
          elseif ($case -eq 'Injected') {'PR213_AFTER_REAL_SETTINGS_FAILURE'} else {''}
        if ($expectedFailure -and -not $err.Result.Contains($expectedFailure)) { $row.problems += 'Original primary failure was not reported by the real child process' }
      }
      if (($case -eq 'Signed' -and $row.exitCode -ne 0) -or ($case -ne 'Signed' -and $row.exitCode -eq 0)) {
        $row.problems += 'Actual child exit code lost success/failure semantics'
      }
      if (-not $row.stdoutCompleteAfterOwnedCleanup -or -not $row.stderrCompleteAfterOwnedCleanup) {
        $row.problems += 'Actual ART test streams stayed open after exact owned retirement'
      }
      $cancel.Cancel()
      $process.StandardOutput.Dispose()
      $process.StandardError.Dispose()
      $process.Dispose()
      $row.handleClosed = $handle.IsClosed
      if (-not $handle.IsClosed) { $row.problems += 'Actual inner native process handle remained open' }
    }
    $cancel.Dispose()
  }
  $serviceLogs = Join-Path $folder 'android/services-logs'
  if (Test-Path $serviceLogs) { Copy-Item -LiteralPath $serviceLogs -Destination (Join-Path $folder 'services-logs') -Recurse }
  $observations.Add([pscustomobject]$row)
  if ($row.problems.Count) { $failures.Add("$case : $($row.problems -join '; ')") }
  Write-Host ('ACTUAL_ART_SETTINGS ' + ($row | ConvertTo-Json -Compress -Depth 10))
}
$result = [ordered]@{source=$env:GITHUB_SHA;run=$env:GITHUB_RUN_ID;attempt=$env:GITHUB_RUN_ATTEMPT;
  historicalRuntimeSource='9eec2089061fb5eb0586cd59da16d56ed7e1dfd2';
  scope='Fresh fixture only over exact historical runtime; selected original Track50 section; not a rebuilt whole-candidate or full services/ARM verdict';
  observations=$observations.ToArray();failures=$failures.ToArray()}
$result | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $Output 'actual-art-result.json')
if ($failures.Count) { throw ($failures -join "`n") }
Write-Host 'PR213 actual ART Settings and owned-device diagnostic completed'
