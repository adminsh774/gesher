param(
  [Parameter(Mandatory)] [string] $Dir,
  [Parameter(Mandatory)] [ValidateSet('Native', 'Java')] [string] $Mode,
  [string] $BootRoot,
  [string] $BootClasspath
)
$ErrorActionPreference = 'Stop'
$evidence = Join-Path $Dir 'child-usage-evidence'
New-Item -ItemType Directory -Force $evidence | Out-Null
$results = [Collections.Generic.List[object]]::new()
function GuestPath([string] $Path) { ($Path -replace '\\', '/') -replace '^[A-Za-z]:', '' }
function RunChildUsage([string] $Name, [string] $Exe, [string[]] $Arguments, [string] $Expected) {
  $start = [Diagnostics.ProcessStartInfo]::new()
  $start.FileName = $Exe
  $start.UseShellExecute = $false
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $start
  try {
    if (-not $process.Start()) { throw "Could not start $Name" }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $exited = $process.WaitForExit(60000)
    if (-not $exited) { $process.Kill($true); $process.WaitForExit() }
    $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    $output | Set-Content -LiteralPath (Join-Path $evidence "$Name.log") -Encoding utf8
    $ok = $exited -and $process.ExitCode -eq 0 -and $output.Contains($Expected)
    $results.Add([ordered]@{ name = $Name; completed = $exited; exit = $process.ExitCode; passed = $ok })
    if ($ok) { Write-Host "PASS child CPU usage: $Name" }
    else { Write-Host "FAIL child CPU usage: $Name (exit $($process.ExitCode), completed=$exited)"; Write-Host $output }
  } finally { $process.Dispose() }
}
if ($Mode -eq 'Native') {
  foreach ($abi in 'x86_64', 'arm64-v8a') {
    $fixture = (Resolve-Path (Join-Path $Dir "child-usage-$abi")).Path
    $guest = GuestPath $fixture
    RunChildUsage "native-$abi" (Join-Path $Dir 'gesher-exec.exe') @(
      '--elf', $guest, 'child-usage', $guest) 'child-usage: SUMMARY failures=0'
  }
} else {
  if (-not $BootRoot -or -not $BootClasspath) { throw 'Java mode requires the actual boot root and classpath' }
  $jni = GuestPath (Resolve-Path (Join-Path $Dir 'libchildusagejni.so')).Path
  $child = GuestPath (Resolve-Path (Join-Path $Dir 'child-usage-x86_64')).Path
  RunChildUsage 'java-libcore-guest' (Join-Path $Dir 'dalvikvm.exe') @(
    "-Xbootclasspath:$BootClasspath", "-Ximage:$(GuestPath "$BootRoot/system")/framework/boot.art",
    '-Xint', '-Xusejit:false', '-cp', (GuestPath "$Dir/exec-test.dex"),
    'gesher.ChildUsageProcessTest', $jni, $child) 'ChildUsageProcessTest all passed'
}
$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence "$Mode-results.json") -Encoding utf8
if (@($results | Where-Object { -not $_.passed }).Count) { exit 1 }
exit 0
