# One-time: creates the app's permanent release signing key in D:\AndroidDev\keys.
#   release.keystore  - the key itself (back this up: without it, no future update can install)
#   release-pass.dat  - its password, encrypted so only your Windows user on this laptop can read it
# Refuses to run if a key already exists, so it can never replace the key by accident.
$ErrorActionPreference = 'Stop'
$keys = 'D:\AndroidDev\keys'
$store = Join-Path $keys 'release.keystore'
$passFile = Join-Path $keys 'release-pass.dat'
if (Test-Path $store) { Write-Host "A release key already exists at $store. Not touching it."; exit 1 }

# A long random password; nobody needs to type it.
$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pass = [Convert]::ToBase64String($bytes).Replace('+', 'A').Replace('/', 'B').TrimEnd('=')

$keytool = Join-Path (Get-ChildItem 'D:\AndroidDev\jdk' -Directory | Select-Object -First 1).FullName 'bin\keytool.exe'
$ErrorActionPreference = 'Continue' # keytool reports progress on stderr
& $keytool -genkeypair -keystore $store -storetype PKCS12 -alias expenselog -keyalg RSA -keysize 4096 `
    -validity 10950 -storepass $pass -keypass $pass -dname "CN=Expense Log, O=Personal" 2>$null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $store)) { Write-Host "keytool failed."; exit 1 }
ConvertTo-SecureString $pass -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $passFile -Encoding ascii
Write-Host "Created $store (valid 30 years). Password saved encrypted in $passFile."
