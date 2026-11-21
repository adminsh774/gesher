param([Parameter(Mandatory)][string] $Output)
$ErrorActionPreference = 'Stop'
$Output = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$failures = [Collections.Generic.List[string]]::new()
$observations = [Collections.Generic.List[object]]::new()
function Ensure-NativeObserver {
  if ('Pr213WindowObserver' -as [type]) { return }
  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class Pr213WindowObserver {
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hwnd, int command);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
'@
}
# The first original helper starts before this driver invokes Add-Type, preserving
# a genuinely cold Windows PowerShell/C# startup observation.
foreach ($index in 1..4) {
  $kind = if ($index % 2 -eq 1) { 'original' } else { 'observed' }
  $folder = Join-Path $Output "foreground-$index-$kind"
  New-Item -ItemType Directory -Path $folder | Out-Null
  $receipt = Join-Path $folder 'window.json'
  $stop = Join-Path $folder 'stop'
  $script = Join-Path $Output "ForeignForeground-$kind.ps1"
  $process = $null
  $handle = $null
  $window = [IntPtr]::Zero
  $row = [ordered]@{ kind='foreground'; case=$index; implementation=$kind;
    startupBudgetMs=15000; retirementBudgetMs=10000; problems=@() }
  try {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    # Keep the exact original launch flags: no output redirection, added compiler
    # prewarm, NonInteractive flag or changed execution-policy prerequisite.
    $process = Start-Process powershell.exe -ArgumentList @('-NoProfile', '-File',
      "`"$script`"", '-Receipt', "`"$receipt`"", '-Stop', "`"$stop`"") -PassThru -WindowStyle Hidden
    $handle = $process.SafeHandle
    $row.pid = $process.Id
    $row.created = $process.StartTime.ToUniversalTime().ToFileTimeUtc()
    while (-not (Test-Path $receipt) -and -not $process.HasExited -and $watch.ElapsedMilliseconds -lt 15000) {
      Start-Sleep -Milliseconds 50
    }
    $row.startupMs = $watch.ElapsedMilliseconds
    $row.receiptPresent = Test-Path $receipt
    $row.exitedBeforeReceipt = $process.HasExited
    if (-not $row.receiptPresent) {
      $row.problems += 'owned foreground window did not start within original budget'
    } else {
      $value = [IO.File]::ReadAllText($receipt) | ConvertFrom-Json
      $window = [IntPtr][long]$value.hwnd
      Ensure-NativeObserver
      [uint32]$windowPid = 0
      $null = [Pr213WindowObserver]::GetWindowThreadProcessId($window, [ref]$windowPid)
      $row.windowPid = $windowPid
      $row.hwnd = [long]$window
      $row.receiptCreated = $value.created
      if ($process.HasExited -or $value.pid -ne $process.Id -or $value.created -le 0 -or
          [long]$value.created -ne $row.created -or $windowPid -ne $process.Id -or
          -not [Pr213WindowObserver]::IsWindow($window)) {
        $row.problems += 'actual native window and retained process identity differ'
      }
      [void][Pr213WindowObserver]::ShowWindowAsync($window, 5)
      $row.foregroundRequest = [Pr213WindowObserver]::SetForegroundWindow($window)
      Start-Sleep -Milliseconds 150
      $row.observedForeground = [long][Pr213WindowObserver]::GetForegroundWindow()
      # Record actual focus; the Android denial assertion remains in Track51 and
      # is not replaced by this standalone native-startup measurement.
    }
  } catch { $row.problems += [string]$_ }
  finally {
    [IO.File]::WriteAllText($stop, 'stop')
    if ($null -ne $process) {
      $retire = [Diagnostics.Stopwatch]::StartNew()
      if (-not $process.WaitForExit(10000)) {
        $row.problems += 'owned foreground helper did not retire within original budget'
        $process.Kill()
        if (-not $process.WaitForExit(5000)) { $row.problems += 'owned helper remained after bounded force cleanup' }
      } elseif ($process.ExitCode -ne 0) { $row.problems += "owned helper exited $($process.ExitCode)" }
      $row.retirementMs = $retire.ElapsedMilliseconds
      if ($process.HasExited) { $row.exitCode = $process.ExitCode }
      $process.Dispose()
      $row.handleClosed = $handle.IsClosed
      if (-not $handle.IsClosed) { $row.problems += 'native process handle remained open' }
    }
    if ($window -ne [IntPtr]::Zero -and [Pr213WindowObserver]::IsWindow($window)) {
      $row.problems += 'native window survived owned process retirement'
    }
  }
  $row.phases = if (Test-Path ($receipt + '.phases.log')) { [IO.File]::ReadAllText($receipt + '.phases.log') } else { '' }
  $observations.Add([pscustomobject]$row)
  if ($row.problems.Count) { $failures.Add("foreground-$index-$kind : $($row.problems -join '; ')") }
  Write-Host ('FOREGROUND ' + ($row | ConvertTo-Json -Compress -Depth 8))
}
function Open-OwnedProcess($Record) {
  try { $process = [Diagnostics.Process]::GetProcessById([int]$Record.pid) }
  catch [ArgumentException] { return $null }
  $handle = $process.SafeHandle
  if ($process.StartTime.ToUniversalTime().ToFileTimeUtc() -ne [long]$Record.created) {
    $process.Dispose()
    throw 'Owned PID was reused; refusing cleanup'
  }
  return [pscustomobject]@{Process=$process; Handle=$handle}
}
foreach ($case in @(@{Mode='Old';Count=3}, @{Mode='Finally';Count=1}, @{Mode='Finally';Count=3})) {
  $folder = Join-Path $Output "owned-$($case.Mode)-$($case.Count)"
  New-Item -ItemType Directory -Path $folder | Out-Null
  $psi = [Diagnostics.ProcessStartInfo]::new((Join-Path $PSHOME 'pwsh.exe'))
  foreach ($arg in @('-NoProfile','-File',(Join-Path $PSScriptRoot 'owned-inner.ps1'),
      '-Mode',$case.Mode,'-Count',[string]$case.Count,'-Root',$folder)) { $psi.ArgumentList.Add($arg) }
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $row = [ordered]@{kind='owned-teardown';mode=$case.Mode;count=$case.Count;problems=@()}
  $process = $null
  $handle = $null
  $children = @()
  $cancel = [Threading.CancellationTokenSource]::new()
  try {
    $process = [Diagnostics.Process]::Start($psi)
    $handle = $process.SafeHandle
    $out = $process.StandardOutput.ReadToEndAsync($cancel.Token)
    $err = $process.StandardError.ReadToEndAsync($cancel.Token)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Path (Join-Path $folder 'primary')) -and -not $process.HasExited -and $watch.ElapsedMilliseconds -lt 60000) {
      Start-Sleep -Milliseconds 50
    }
    $row.primaryRecorded = Test-Path (Join-Path $folder 'primary')
    if (-not $row.primaryRecorded) { throw 'Inner control did not reach the exact injected primary failure' }
    $records = @([IO.File]::ReadAllText((Join-Path $folder 'owned.json')) | ConvertFrom-Json)
    $row.innerExitedBeforeChildCleanup = $process.WaitForExit(15000)
    $row.stdoutCompleteBeforeChildCleanup = $out.IsCompleted
    $row.stderrCompleteBeforeChildCleanup = $err.IsCompleted
    foreach ($record in $records) {
      $child = Open-OwnedProcess $record
      if ($null -ne $child) { $children += $child }
    }
    $row.liveOwnedChildrenBeforeCleanup = @($children | Where-Object { -not $_.Process.HasExited }).Count
    if ($case.Mode -eq 'Old' -and $row.liveOwnedChildrenBeforeCleanup -ne $case.Count) {
      $row.problems += 'Old control did not retain the explicitly started children'
    }
    if ($case.Mode -eq 'Finally') {
      if (-not $row.innerExitedBeforeChildCleanup -or $row.liveOwnedChildrenBeforeCleanup -ne 0) {
        $row.problems += 'Finally control failed bounded owned-child retirement'
      }
      if (-not (Test-Path (Join-Path $folder 'cleanup.json'))) { $row.problems += 'No inner owned-handle cleanup receipt' }
      else {
        $row.cleanup = @([IO.File]::ReadAllText((Join-Path $folder 'cleanup.json')) | ConvertFrom-Json)
        if (@($row.cleanup | Where-Object { $_.problem -or -not $_.closed }).Count -gt 0) {
          $row.problems += 'Inner cleanup failed or retained a native process handle'
        }
      }
    }
  } catch { $row.problems += [string]$_ }
  finally {
    # Cooperative stop is scoped to files created solely for these controlled
    # children; retain the native identity check before any process operation.
    foreach ($index in 1..$case.Count) {
      [IO.File]::WriteAllText((Join-Path $folder "child-$index.stop"), 'stop')
    }
    foreach ($child in $children) {
      try {
        if (-not $child.Process.HasExited) { $child.Process.Kill($true) }
        if (-not $child.Process.WaitForExit(5000)) { $row.problems += 'Outer owned-child cleanup exceeded five seconds' }
      } catch { $row.problems += "Outer owned-child cleanup: $_" }
      finally { $child.Process.Dispose() }
      if (-not $child.Handle.IsClosed) { $row.problems += 'Outer owned process handle remained open' }
    }
    if ($null -ne $process) {
      $row.innerExitedAfterChildCleanup = $process.WaitForExit(5000)
      if (-not $row.innerExitedAfterChildCleanup) {
        $row.problems += 'Inner process still alive after owned-child retirement'
        $process.Kill($true)
        [void]$process.WaitForExit(5000)
      }
      $row.exitCode = if ($process.HasExited) { $process.ExitCode } else { $null }
      $row.stdoutCompleteAfterChildCleanup = $out.Wait(5000)
      $row.stderrCompleteAfterChildCleanup = $err.Wait(5000)
      if ($row.stdoutCompleteAfterChildCleanup) {
        [IO.File]::WriteAllText((Join-Path $folder 'inner.out'), $out.Result)
      }
      if ($row.stderrCompleteAfterChildCleanup) {
        [IO.File]::WriteAllText((Join-Path $folder 'inner.err'), $err.Result)
        if (-not $err.Result.Contains('PR213_PRIMARY_FAILURE_SENTINEL')) { $row.problems += 'Original failure was not preserved' }
      }
      if ($row.exitCode -eq 0) { $row.problems += 'Original failure became success' }
      if (-not $row.stdoutCompleteAfterChildCleanup -or -not $row.stderrCompleteAfterChildCleanup) {
        $row.problems += 'Redirected streams did not finish after owned cleanup'
      }
      $cancel.Cancel()
      $process.StandardOutput.Dispose()
      $process.StandardError.Dispose()
      $process.Dispose()
      $row.handleClosed = $handle.IsClosed
      if (-not $handle.IsClosed) { $row.problems += 'Inner native process handle remained open' }
    }
    $cancel.Dispose()
  }
  $observations.Add([pscustomobject]$row)
  if ($row.problems.Count) { $failures.Add("owned-$($case.Mode)-$($case.Count): $($row.problems -join '; ')") }
  Write-Host ('OWNED_TEARDOWN ' + ($row | ConvertTo-Json -Compress -Depth 8))
}
$result = [ordered]@{run=$env:GITHUB_RUN_ID;attempt=$env:GITHUB_RUN_ATTEMPT;source=$env:GITHUB_SHA;
  os=[Environment]::OSVersion.ToString();powershell=$PSVersionTable.PSVersion.ToString();
  scope='Exact helper startup, and isolated Windows process/redirected-pipe ownership; not full ART teardown or Android clipboard admission';
  observations=$observations.ToArray();failures=$failures.ToArray()}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $Output 'result.json')
if ($failures.Count) { throw ($failures -join "`n") }
Write-Host 'PR213 isolated fixture-process measurement completed'
