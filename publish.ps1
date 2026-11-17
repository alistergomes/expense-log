# Publishes an APK to the "ExpenseLog" folder of your OneDrive, where the app on the phone picks it up
# and installs it by itself. Uses only the free Microsoft Graph API with your personal account.
#
#   .\publish.ps1 -SignIn                  one-time: sign this laptop in to your OneDrive
#   .\publish.ps1 -Notes "what's new"      publish .\ExpenseLog.apk (build.ps1 calls this)
#
# Each release is also recorded in D:\AndroidDev\releases\<versionCode>\ (APK + source snapshot), and
# marked as "being watched" so watchdog.ps1 can roll it back if it fails on the phone.
param([switch]$SignIn, [string]$Notes = "", [string]$Apk = "", [string]$SourceDir = "", [switch]$IsRollback)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
. "$PSScriptRoot\onedrive.ps1"

if ($SignIn) { Start-OneDriveSignIn; exit $LASTEXITCODE }

if (-not $Apk) { $Apk = Join-Path $PSScriptRoot 'ExpenseLog.apk' }
if (-not $SourceDir) { $SourceDir = $PSScriptRoot }
if (-not (Test-Path $Apk)) { Write-Host "APK not found: $Apk"; exit 1 }

# Version comes from the APK itself, so version.json always matches the file being published.
$aapt = Get-ChildItem 'D:\AndroidDev\sdk\build-tools' -Recurse -Filter aapt2.exe | Select-Object -First 1
$badge = & $aapt.FullName dump badging $Apk 2>$null | Select-Object -First 1
$code = [int]([regex]::Match($badge, "versionCode='(\d+)'").Groups[1].Value)
$name = [regex]::Match($badge, "versionName='([^']+)'").Groups[1].Value

$headers = Get-OneDriveHeaders
if (-not $headers) { exit 2 }

# Before replacing the watched release: if the phone reported it working, it becomes the known-good one.
$watch = Read-Release 'watch.json'
if ($watch -and -not $watch.done) {
    $h = Get-Health $headers
    if ($h -and [int]$h.versionCode -eq [int]$watch.code -and $h.state -eq 'ok') {
        Write-Release 'good.json' @{ code = $watch.code; name = $watch.name }
    }
}

# Keep this release's APK and source, so it can be rebuilt later as a rollback.
$dir = Join-Path $ReleasesDir $code
New-Item -ItemType Directory -Force "$dir\src\app" | Out-Null
Copy-Item $Apk "$dir\ExpenseLog.apk" -Force
foreach ($f in 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties') { Copy-Item (Join-Path $SourceDir $f) "$dir\src\$f" -Force }
Copy-Item (Join-Path $SourceDir 'app\build.gradle.kts') "$dir\src\app\build.gradle.kts" -Force
if (Test-Path "$dir\src\app\src") { Remove-Item "$dir\src\app\src" -Recurse -Force }
Copy-Item (Join-Path $SourceDir 'app\src') "$dir\src\app\src" -Recurse -Force

# APK first, version.json last: the phone only sees the new version once the file it points to is there.
Invoke-RestMethod -Method Put -Uri "$Graph/me/drive/root:/ExpenseLog/ExpenseLog.apk:/content" -Headers $headers `
    -InFile $Apk -ContentType 'application/vnd.android.package-archive' | Out-Null
$json = @{ versionCode = $code; versionName = $name; notes = $Notes; published = (Get-Date -Format 'yyyy-MM-dd HH:mm') } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$Graph/me/drive/root:/ExpenseLog/version.json:/content" -Headers $headers `
    -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json' | Out-Null

Write-Release 'watch.json' @{
    code = $code; name = $name; publishedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    isRollback = [bool]$IsRollback; done = $false
}
Write-Host "Published version $name ($code) to OneDrive folder ExpenseLog. The watchdog is now watching it."
