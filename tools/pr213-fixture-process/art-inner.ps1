param(
  [Parameter(Mandatory)][string] $Dir,
  [Parameter(Mandatory)][string] $CaseRoot,
  [Parameter(Mandatory)][string] $Extracted,
  [Parameter(Mandatory)][ValidateSet('Unsigned','Signed','Injected')][string] $Case
)
$ErrorActionPreference = 'Stop'
$failed = 0
$owned = [Collections.Generic.List[object]]::new()
$originalFailure = $null
function Write-Phase([string] $Phase) {
  [IO.File]::AppendAllText((Join-Path $CaseRoot 'phases.log'),
    [DateTime]::UtcNow.ToString('o') + ' ' + $Phase + [Environment]::NewLine)
}
function Observe-Owned([Diagnostics.Process] $Process, [string] $Role) {
  $handle = $Process.SafeHandle
  $owned.Add([pscustomobject]@{Process=$Process;Handle=$handle;Role=$Role;Pid=$Process.Id;
    Created=$Process.StartTime.ToUniversalTime().ToFileTimeUtc()})
  $rows = @($owned | ForEach-Object {
    [pscustomobject]@{pid=$_.Pid;created=$_.Created;role=$_.Role}
  })
  $path = Join-Path $CaseRoot 'owned.json'
  [IO.File]::WriteAllText($path + '.new', ($rows | ConvertTo-Json -AsArray))
  [IO.File]::Move($path + '.new', $path, $true)
  Write-Phase "owned role=$Role pid=$($Process.Id)"
}
function NoDrive([string] $p) { ($p -replace '\\', '/') -replace '^[A-Za-z]:', '' }
$root = Join-Path $CaseRoot 'android'
foreach ($d in 'system/framework','art','data','i18n/etc/icu','tzdata') {
  New-Item -ItemType Directory -Force (Join-Path $root $d) | Out-Null
}
Copy-Item "$Dir/icudt*l.dat" (Join-Path $root 'i18n/etc/icu')
if (Test-Path "$Dir/tzdata") { Copy-Item -Recurse -Force "$Dir/tzdata/*" (Join-Path $root 'tzdata') }
$env:ANDROID_ROOT = NoDrive "$root/system"
$env:ANDROID_ART_ROOT = NoDrive "$root/art"
$env:ANDROID_DATA = NoDrive "$root/data"
$env:ANDROID_I18N_ROOT = NoDrive "$root/i18n"
$env:ANDROID_TZDATA_ROOT = NoDrive "$root/tzdata"
if (-not (Test-Path "$Dir/framework/x86_64/boot.art")) { throw 'Historical installer has no required real boot image' }
New-Item -ItemType Directory -Force (Join-Path $root 'system/framework/x86_64') | Out-Null
Copy-Item "$Dir/framework/x86_64/*" (Join-Path $root 'system/framework/x86_64')
$env:GESHER_REQUIRE_BOOT_IMAGE = '1'
Copy-Item "$Dir/framework-res.apk" (Join-Path $root 'system/framework')
Copy-Item "$Dir/build.prop" (Join-Path $root 'system')
$bcpApp = (('core-oj','core-libart','core-icu4j','framework','gesher-app') |
  ForEach-Object { NoDrive "$Dir/$_.jar" }) -join ':'
$logs = Join-Path $root 'services-logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$env:GESHER_LOCALES = 'en-US'
$env:GESHER_CI_PROCESS_TRACE = '1'
. "$PSScriptRoot/../../tests/windows/helpers/ProcessReaderHandles.ps1"
. "$PSScriptRoot/../../tests/windows/helpers/CloseProcessReaders.ps1"
. (Join-Path $Extracted 'functions.ps1')
try {
  Write-Phase "track-enter case=$Case"
  . (Join-Path $Extracted 'prefix.ps1')
  if ($failed -ne 0) { throw "Original Track50 assertions failed: $failed" }
  [IO.File]::WriteAllText((Join-Path $CaseRoot 'completed'), 'Original Track50 prefix completed')
  Write-Phase 'track-complete'
} catch {
  $originalFailure = $_
  [IO.File]::WriteAllText((Join-Path $CaseRoot 'failure'), [string]$_)
  Write-Phase ('track-error ' + [string]$_)
  [Console]::Error.WriteLine('ACTUAL_TRACK_FAILURE_BEGIN')
  [Console]::Error.WriteLine([string]$originalFailure)
  [Console]::Error.WriteLine('ACTUAL_TRACK_FAILURE_END')
  throw
} finally {
  # Unsigned reproduces the original early-throw ownership path. The controller
  # captures exact PID/generation handles and always retires those real children.
  # The other cases measure a proposed finally boundary; no product code uses it.
  if ($Case -ne 'Unsigned') {
    $cleanup = @()
    for ($index = $owned.Count - 1; $index -ge 0; $index--) {
      $record = $owned[$index]
      $problem = ''
      $exited = $false
      try {
        if (-not $record.Handle.IsClosed) {
          if (-not $record.Process.HasExited) { $record.Process.Kill($true) }
          $exited = $record.Process.WaitForExit(5000)
          if (-not $exited) { throw 'Owned ART fixture process did not retire within five seconds' }
        } else { throw 'Owned ART process handle closed before this boundary could prove retirement' }
      } catch { $problem = [string]$_ }
      finally { $record.Process.Dispose() }
      $cleanup += [pscustomobject]@{role=$record.Role;pid=$record.Pid;created=$record.Created;
        exited=$exited;closed=$record.Handle.IsClosed;problem=$problem}
      Write-Phase "owned-retire role=$($record.Role) exited=$exited closed=$($record.Handle.IsClosed)"
    }
    $cleanup | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $CaseRoot 'cleanup.json')
    $problems = @($cleanup | Where-Object { $_.problem -or -not $_.closed -or -not $_.exited })
    if ($problems.Count) {
      if ($null -ne $originalFailure) { Write-Error -ErrorAction Continue ('Owned ART cleanup: ' + ($problems | ConvertTo-Json -Compress)) }
      else { throw 'Owned ART fixture cleanup failed' }
    }
  }
}
