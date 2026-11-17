# Builds the APK using the toolchain in D:\AndroidDev (nothing is added to the system PATH), then releases it.
#
# The phone installs whatever is published to OneDrive BY ITSELF (automatic updates).
# Since 2026-11-17 the owner releases without the USB cable (their choice):
#   - No phone on USB (normal): tests + signed build, then publish straight to OneDrive. The safety net is
#     the laptop watchdog, which rolls back a version that crashes on the phone (laptop must be on).
#   - Phone on USB (optional):  also install it, launch it, and publish ONLY if it starts without crashing.
#
#   .\build.ps1 -Notes "what's new"     release
#   .\build.ps1 -NoPublish              build (and launch-test if a phone is plugged in) without releasing
#   .\build.ps1 -RequirePhone           refuse to publish unless the launch test ran (the old, stricter rule)
param([string]$Notes = "", [switch]$NoPublish, [switch]$RequirePhone, [switch]$PublishUntested)

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
} elseif ($tested -or -not $RequirePhone) {
    if (-not $tested) {
        Write-Host "Publishing without a launch test (no phone on USB). If it crashes on the phone, the watchdog rolls it back."
        $good = 'D:\AndroidDev\releases\good.json'
        if (-not (Test-Path $good)) { Write-Host "WARNING: no known-good version recorded yet, so an automatic rollback isn't possible." }
    }
    & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\publish.ps1" -Notes $Notes
    # Make sure the watchdog runs (every 15 min) so a release that fails on the phone is rolled back.
    $task = 'ExpenseLog Watchdog'
    & schtasks /query /tn $task 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        & schtasks /create /tn $task /sc minute /mo 15 /f /tr "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\watchdog.ps1`"" | Out-Null
        Write-Host "Scheduled the watchdog (every 15 minutes)."
    }
} else {
    Write-Host "NOT published: -RequirePhone was given but no phone is plugged in."
}
