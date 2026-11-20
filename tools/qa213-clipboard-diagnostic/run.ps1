param([string]$Dir = 'dist', [string]$Fixture = 'clipboard-fixture')
$ErrorActionPreference = 'Stop'
function NoDrive([string]$p) { ($p -replace '\\', '/') -replace '^[A-Za-z]:', '' }
$Dir = (Resolve-Path $Dir).Path
$root = Join-Path $env:RUNNER_TEMP 'qa213-clipboard-source'
foreach ($d in 'system/framework', 'art', 'data', 'i18n/etc/icu', 'tzdata') { New-Item -ItemType Directory -Force (Join-Path $root $d) | Out-Null }
Copy-Item "$Dir/icudt*l.dat" (Join-Path $root 'i18n/etc/icu')
if (Test-Path "$Dir/tzdata") { Copy-Item -Recurse -Force "$Dir/tzdata/*" (Join-Path $root 'tzdata') }
New-Item -ItemType Directory -Force (Join-Path $root 'system/framework/x86_64') | Out-Null
Copy-Item "$Dir/framework/x86_64/*" (Join-Path $root 'system/framework/x86_64')
Copy-Item "$Dir/framework-res.apk" (Join-Path $root 'system/framework')
Copy-Item "$Dir/build.prop" (Join-Path $root 'system')
$env:ANDROID_ROOT = NoDrive "$root/system"
$env:ANDROID_ART_ROOT = NoDrive "$root/art"
$env:ANDROID_DATA = NoDrive "$root/data"
$env:ANDROID_I18N_ROOT = NoDrive "$root/i18n"
$env:ANDROID_TZDATA_ROOT = NoDrive "$root/tzdata"
$env:GESHER_REQUIRE_BOOT_IMAGE = '1'
$env:GESHER_TRACE_FAULTS = '1'
$bcp = (('core-oj','core-libart','core-icu4j','framework','gesher-app') | ForEach-Object { NoDrive "$Dir/$_.jar" }) -join ':'
$dex = (Resolve-Path "$Fixture/services-tests.dex").Path
$psi = [Diagnostics.ProcessStartInfo]::new("$Dir/dalvikvm.exe")
$psi.UseShellExecute = $false; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
foreach ($argument in @("-Xbootclasspath:$bcp", "-Ximage:$(NoDrive "$root/system")/framework/boot.art", '-Xint', '-Xusejit:false', '-cp', (NoDrive $dex), 'gesher.services.ServiceFixtureRunner', 'ClipboardSourceDiagnostic')) { $psi.ArgumentList.Add($argument) }
$p = [Diagnostics.Process]::Start($psi)
$stdout = $p.StandardOutput.ReadToEndAsync(); $stderr = $p.StandardError.ReadToEndAsync()
if (-not $p.WaitForExit(120000)) { $p.Kill($true); throw 'Actual ART clipboard diagnostic timed out' }
if (-not $stdout.Wait(10000) -or -not $stderr.Wait(10000)) { throw 'Diagnostic output readers did not retire' }
$text = $stdout.Result + "`n" + $stderr.Result
$text | Set-Content -Encoding utf8 'clipboard-art.txt'
Write-Host $text
if ($p.ExitCode -ne 0 -or -not $text.Contains('CLIP_SOURCE diagnostic completed with exact original negative and real admission observations')) { throw "Actual ART diagnostic failed: $($p.ExitCode)" }
$p.Dispose()
