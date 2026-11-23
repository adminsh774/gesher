# Local driver: run-tests.ps1's environment, then tests/windows/tracks/54-web3.ps1 with the
# device's non-proxy sections and the Windows'-settings section cut (Internet Options are
# not touched); Gesher's own proxy section (CLI + --proxy + the control panel) runs as is.
param([Parameter(Mandatory)] [string] $Dir, [Parameter(Mandatory)] [string] $Repo, [Parameter(Mandatory)] [string] $Work)
$ErrorActionPreference = 'Stop'
$failed = 0
$Dir = (Resolve-Path $Dir).Path
$env:RUNNER_TEMP = $Work
New-Item -ItemType Directory -Force $Work | Out-Null
function NoDrive([string] $p) { ($p -replace '\\', '/') -replace '^[A-Za-z]:', '' }
function Check([string] $Name, [scriptblock] $Run, [string[]] $Expect) {
  $out = & $Run 2>&1 | Out-String
  $missing = $Expect | Where-Object { -not $out.Contains($_) }
  if ($LASTEXITCODE -eq 0 -and -not $missing) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name"; $script:failed++ }
}
$root = Join-Path $Work 'gesher-root'
foreach ($d in 'system/framework', 'art', 'data', 'i18n/etc/icu', 'tzdata') { New-Item -ItemType Directory -Force (Join-Path $root $d) | Out-Null }
Copy-Item "$Dir/icudt*l.dat" (Join-Path $root 'i18n/etc/icu')
if (Test-Path "$Dir/tzdata") { Copy-Item -Recurse -Force "$Dir/tzdata/*" (Join-Path $root 'tzdata') }
$env:ANDROID_ROOT = NoDrive "$root/system"
$env:ANDROID_ART_ROOT = NoDrive "$root/art"
$env:ANDROID_DATA = NoDrive "$root/data"
$env:ANDROID_I18N_ROOT = NoDrive "$root/i18n"
$env:ANDROID_TZDATA_ROOT = NoDrive "$root/tzdata"
New-Item -ItemType Directory -Force (Join-Path $root 'system/framework/x86_64') | Out-Null
Copy-Item "$Dir/framework/x86_64/*" (Join-Path $root 'system/framework/x86_64')
$env:GESHER_REQUIRE_BOOT_IMAGE = '1'
Copy-Item "$Dir/framework-res.apk" (Join-Path $root 'system/framework')
Copy-Item "$Dir/build.prop" (Join-Path $root 'system')

$trackPath = Join-Path $Repo 'tests/windows/tracks/54-web3.ps1'
$text = [IO.File]::ReadAllText($trackPath)
$cutFrom = $text.IndexOf("  `$before = ([GesherWeb3Servers]::Log -split ""``n"").Count - 1`n  `$null = Web3Run ""the Gesher device: `$compatName")
$cutTo = $text.IndexOf("  # Gesher's own proxy (com.gesher.net.ProxySetting)")
if ($cutFrom -lt 0 -or $cutTo -le $cutFrom) { throw "track boundaries changed ($cutFrom, $cutTo)" }
# The single-process runs before the device section are not proxy runs either.
$singleFrom = $text.IndexOf('$null = Web3Run ''selected Network: real socket/process routing')
$singleTo = $text.IndexOf('$savedData = $env:ANDROID_DATA')
if ($singleFrom -lt 0 -or $singleTo -le $singleFrom) { throw "single-process boundaries changed" }
$trimmed = $text.Substring(0, $singleFrom) + $text.Substring($singleTo, $cutFrom - $singleTo) + $text.Substring($cutTo)
# The single-process WebView compat run and the screen-reader (a11y) run.
$a = $trimmed.IndexOf("`$before = ([GesherWeb3Servers]::Log -split ""``n"").Count - 1`n`$null = Web3Run ""`$compatName (single process)""")
$b = $trimmed.IndexOf('# --- navigator.geolocation')
if ($a -lt 0 -or $b -le $a) { throw "compat boundaries changed ($a, $b)" }
$trimmed = $trimmed.Remove($a, $b - $a)
$a = $trimmed.IndexOf("Web3ScreenReader `$true`ntry {")
$b = $trimmed.IndexOf('# --- The Gesher device: app processes')
if ($a -lt 0 -or $b -le $a) { throw "a11y boundaries changed ($a, $b)" }
$trimmed = $trimmed.Remove($a, $b - $a)
if ($trimmed.Contains('ScreenReader $true') -or $trimmed.Contains('SetServer(')) { throw 'a settings-changing step is still in' }
# Earlier expectation definitions may reference skipped runs; keep everything else verbatim.
$trimmedPath = Join-Path $Repo 'tests/windows/tracks/_local-54-web3-proxy.ps1'
[IO.File]::WriteAllText($trimmedPath, $trimmed, [Text.UTF8Encoding]::new($true))
try {
  $PSScriptRootForTrack = Split-Path $trackPath
  . $trimmedPath
} finally { Remove-Item -LiteralPath $trimmedPath -ErrorAction SilentlyContinue }
Write-Host "LOCAL RESULT: failed=$failed"
