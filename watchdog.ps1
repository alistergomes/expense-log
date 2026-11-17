# Watches the latest published release and rolls it back if it fails on the phone.
# Runs every 15 minutes as the Windows scheduled task "ExpenseLog Watchdog" (free, local).
#
# The phone writes ExpenseLog/health.json in OneDrive: installed -> ok, or crashed.
#   crashed                                   -> roll back
#   installed, but no "ok" after 30 minutes   -> roll back (the app never managed to start)
#   ok for 2 hours                            -> confirmed: becomes the known-good version
# Rolling back = rebuilding the last known-good source under a higher version number and publishing it;
# the phone then installs it like any update (Android never allows going to a lower number).
# Failures are written to UPDATE-FAILURES.md in the project so Claude sees them in the next session.
param([int]$StuckMinutes = 30, [int]$ConfirmMinutes = 120)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
. "$PSScriptRoot\onedrive.ps1"
$FailLog = Join-Path $PSScriptRoot 'UPDATE-FAILURES.md'

function Alert([string]$title, [string]$text) {
    Write-Host "$title - $text"
    try {
        Add-Type -AssemblyName System.Windows.Forms, System.Drawing
        $n = New-Object System.Windows.Forms.NotifyIcon
        $n.Icon = [System.Drawing.SystemIcons]::Warning
        $n.Visible = $true
        $n.ShowBalloonTip(20000, $title, $text, [System.Windows.Forms.ToolTipIcon]::Warning)
        Start-Sleep -Seconds 21
        $n.Dispose()
    } catch {}
}

function Log-Failure([string]$text) {
    if (-not (Test-Path $FailLog)) {
        "# Update failures`n`nWritten by watchdog.ps1. Claude: read this at the start of a session, fix the cause, release a new version, then mark the entry FIXED.`n" |
            Set-Content $FailLog -Encoding utf8
    }
    "`n## $(Get-Date -Format 'yyyy-MM-dd HH:mm') - OPEN`n$text`n" | Add-Content $FailLog -Encoding utf8
}

$watch = Read-Release 'watch.json'
if (-not $watch -or $watch.done) { exit 0 }

$headers = Get-OneDriveHeaders
if (-not $headers) {
    Alert 'Expense Log watchdog needs you' 'The laptop is signed out of OneDrive, so it cannot watch updates. Open a terminal in the project and run: .\publish.ps1 -SignIn'
    exit 2
}

$h = Get-Health $headers
if (-not $h -or [int]$h.versionCode -ne [int]$watch.code) { exit 0 }   # phone has not installed it yet

$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$minutesInstalled = ($now - [long]$h.installedAt) / 60000
$reason = $null
if ($h.state -eq 'crashed') { $reason = "Version $($watch.name) crashed on the phone: $($h.detail)" }
elseif ($h.state -eq 'installed' -and $minutesInstalled -ge $StuckMinutes) {
    $reason = "Version $($watch.name) was installed $([int]$minutesInstalled) minutes ago but never started successfully."
}
elseif ($h.state -eq 'ok' -and $minutesInstalled -ge $ConfirmMinutes) {
    Write-Release 'good.json' @{ code = $watch.code; name = $watch.name }
    $watch.done = $true; Write-Release 'watch.json' $watch
    Write-Host "Version $($watch.name) confirmed working."
    exit 0
}
if (-not $reason) { exit 0 }

# ---- roll back ----
$watch.done = $true; Write-Release 'watch.json' $watch
$good = Read-Release 'good.json'
if ($watch.isRollback -or -not $good -or -not (Test-Path (Join-Path $ReleasesDir "$($good.code)\src"))) {
    $why = if ($watch.isRollback) { 'the rollback version itself failed' } else { 'there is no known-good version to go back to' }
    Log-Failure "$reason`nAutomatic rollback NOT possible: $why. Manual fix needed."
    Alert 'Expense Log update failed - needs Claude' "$reason Automatic rollback is not possible ($why). Open Claude and say: check the failed update."
    exit 1
}

$newCode = [int]$watch.code + 1
$newName = "$($good.name)-r$newCode"
$work = 'D:\AndroidDev\rollback-build'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
Copy-Item (Join-Path $ReleasesDir "$($good.code)\src") $work -Recurse
# The project's local.properties (SDK path and personal IDs) is not in the snapshot; reuse it.
Copy-Item (Join-Path $PSScriptRoot 'local.properties') "$work\local.properties" -Force
. "$PSScriptRoot\release-env.ps1"   # toolchain + release key password: rollbacks are release-signed too
& $Gradle --no-daemon -q -p $work assembleRelease "-PvcOverride=$newCode" "-PvnOverride=$newName"
$apk = Join-Path $work $ReleaseApkRel
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $apk)) {
    Log-Failure "$reason`nAutomatic rollback FAILED: rebuilding version $($good.name) did not succeed."
    Alert 'Expense Log rollback failed - needs Claude' "$reason The rollback build failed. Open Claude and say: check the failed update."
    exit 1
}
& powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\publish.ps1" -Apk $apk -SourceDir $work -IsRollback `
    -Notes "Rolled back to $($good.name) because $($watch.name) failed."

Log-Failure "$reason`nRolled back automatically: republished $($good.name) as $newName ($newCode). The phone installs it by itself. Fix the cause and release a new version."
Alert 'Expense Log update rolled back' "$reason Your phone is getting $($good.name) back as $newName. Tell Claude: check the failed update."
