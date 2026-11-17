# Builds the APK using the toolchain in D:\AndroidDev (nothing is added to the system PATH), then releases it.
#
# The phone installs whatever is published to OneDrive BY ITSELF (automatic updates), so a build that
# crashes on startup would break the app with no way to fix it remotely. Therefore:
#   - Phone on USB:  install it, launch it, and publish ONLY if it starts without crashing.
#   - No phone:      do NOT publish, unless -PublishUntested is given deliberately.
#
#   .\build.ps1 -Notes "what's new"     normal release (phone plugged in)
#   .\build.ps1 -NoPublish              build (and USB-install if plugged in) without releasing
#   .\build.ps1 -PublishUntested        release without a launch test (only for trivial changes)
param([string]$Notes = "", [switch]$NoPublish, [switch]$PublishUntested)

. "$PSScriptRoot\release-env.ps1"   # toolchain + release key password
$gradle = $Gradle
$adb = 'D:\AndroidDev\sdk\platform-tools\adb.exe'
$pkg = 'com.expenselog.app'
Set-Location $PSScriptRoot

# Release build, signed with the permanent release key (#76).
if (-not $env:RELEASE_STORE_PASSWORD) { Write-Host "No release key password found (D:\AndroidDev\keys\release-pass.dat)."; exit 1 }
& $gradle --no-daemon testDebugUnitTest assembleRelease
if ($LASTEXITCODE -ne 0) { exit 1 }
if (-not (Test-Path $ReleaseApkRel)) { Write-Host "The release APK was not produced (is the release key set up?)."; exit 1 }
Copy-Item $ReleaseApkRel 'ExpenseLog.apk' -Force
# Make sure it really is signed with the release key, never the old debug key.
$apksigner = (Get-ChildItem 'D:\AndroidDev\sdk\build-tools' -Recurse -Filter apksigner.bat | Select-Object -First 1).FullName
$cert = (& $apksigner verify --print-certs 'ExpenseLog.apk' 2>$null | Select-String 'Signer #1 certificate DN') -join ''
if ($cert -notmatch 'CN=Expense Log') { Write-Host "STOP: ExpenseLog.apk is not signed with the release key ($cert)."; exit 1 }
Write-Host "Built: $PSScriptRoot\ExpenseLog.apk (release-signed)"

$phone = @(& $adb devices | Select-String -Pattern "\tdevice$").Count -ge 1
$tested = $false
if ($phone) {
    $inst = (& $adb install -r 'ExpenseLog.apk' 2>&1 | Select-Object -Last 1) -join ''
    Write-Host $inst
    if ($inst -notmatch 'Success') {
        Write-Host "LAUNCH TEST FAILED: could not install on the phone ($inst). NOT published."
        exit 1
    }
    # Launch test: start the app, give it a few seconds, and look for a crash from this package.
    & $adb logcat -c
    & $adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 6
    $crash = & $adb logcat -d AndroidRuntime:E *:S | Select-String -Pattern "Process: $pkg" -Context 0,3
    $alive = (& $adb shell pidof $pkg) -ne $null
    if ($crash -or -not $alive) {
        Write-Host "LAUNCH TEST FAILED: the app crashed on startup. NOT published."
        $crash | Select-Object -First 1 | ForEach-Object { Write-Host $_.Context.PostContext[0] }
        exit 1
    }
    Write-Host "Launch test passed: the app starts on the phone."
    $tested = $true
} else {
    Write-Host "No phone on USB: this build has not been launched anywhere."
}

if ($NoPublish) {
    Write-Host "Not published (-NoPublish)."
} elseif ($tested -or $PublishUntested) {
    if (-not $tested) { Write-Host "WARNING: publishing a build that was never launched (-PublishUntested)." }
    & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\publish.ps1" -Notes $Notes
    # Make sure the watchdog runs (every 15 min) so a release that fails on the phone is rolled back.
    $task = 'ExpenseLog Watchdog'
    & schtasks /query /tn $task 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        & schtasks /create /tn $task /sc minute /mo 15 /f /tr "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\watchdog.ps1`"" | Out-Null
        Write-Host "Scheduled the watchdog (every 15 minutes)."
    }
} else {
    Write-Host "NOT published: plug the phone in so the build can be launch-tested, or pass -PublishUntested."
}
