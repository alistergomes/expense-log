# Backs up the app's signing key to your OneDrive, in the folder "ExpenseLog-keys-backup":
#   release.keystore      - the release signing key (every version must be signed with it)
#   release-password.txt  - its password, in plain text (the owner chose plain text over a passphrase)
#   RESTORE.txt           - how to put them back on a new laptop
# The laptop's OneDrive sign-in token is deliberately NOT included (it only works on this laptop).
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
. "$PSScriptRoot\onedrive.ps1"
. "$PSScriptRoot\release-env.ps1"   # decrypts the key password into $env:RELEASE_STORE_PASSWORD

$keys = 'D:\AndroidDev\keys'
$store = Join-Path $keys 'release.keystore'
if (-not (Test-Path $store)) { Write-Host "No release key at $store."; exit 1 }
if (-not $env:RELEASE_STORE_PASSWORD) { Write-Host "Couldn't read the key password."; exit 1 }

$headers = Get-OneDriveHeaders
if (-not $headers) { exit 2 }
$folder = "$Graph/me/drive/root:/ExpenseLog-keys-backup"

function Put([string]$name, [byte[]]$bytes) {
    $r = Invoke-RestMethod -Method Put -Uri "${folder}/${name}:/content" -Headers $headers `
        -Body $bytes -ContentType 'application/octet-stream'
    Write-Host ("  {0,-22} {1,8} bytes" -f $r.name, $r.size)
}

$restore = @"
Expense Log: signing key backup ($(Get-Date -Format 'yyyy-MM-dd'))

To set up a new laptop:
1. Copy release.keystore to D:\AndroidDev\keys\release.keystore
2. In PowerShell, store the password for the build scripts (encrypted for your Windows user):
     `$p = Get-Content .\release-password.txt
     ConvertTo-SecureString `$p -AsPlainText -Force | ConvertFrom-SecureString | Set-Content D:\AndroidDev\keys\release-pass.dat -Encoding ascii
3. Sign the laptop in to OneDrive for publishing:  .\publish.ps1 -SignIn
See docs/SETUP.md in the repository. Never create a new key: the app only accepts updates signed with this one.
Key alias: expenselog
"@

Write-Host "Uploading to OneDrive folder ExpenseLog-keys-backup:"
Put 'release.keystore' ([IO.File]::ReadAllBytes($store))
Put 'release-password.txt' ([Text.Encoding]::UTF8.GetBytes($env:RELEASE_STORE_PASSWORD))
Put 'RESTORE.txt' ([Text.Encoding]::UTF8.GetBytes($restore))

# Read the key back and compare, so a damaged upload can't go unnoticed.
$back = Invoke-WebRequest -Uri "${folder}/release.keystore:/content" -Headers $headers -UseBasicParsing
$localHash = (Get-FileHash $store -Algorithm SHA256).Hash
$remoteHash = (Get-FileHash -InputStream ([IO.MemoryStream]::new([byte[]]$back.Content)) -Algorithm SHA256).Hash
if ($localHash -eq $remoteHash) { Write-Host "Verified: the key in OneDrive is identical to the one on this laptop." }
else { Write-Host "WARNING: the uploaded key doesn't match. Run this again."; exit 1 }
