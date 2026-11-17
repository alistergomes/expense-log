# Shared helpers for publish.ps1 and watchdog.ps1 (dot-sourced). Free Microsoft Graph only.
# Your "Expense Log" app registration ID, read from local.properties (not uploaded to GitHub).
$ClientId = ((Get-Content (Join-Path $PSScriptRoot 'local.properties') -ErrorAction SilentlyContinue) |
    Where-Object { $_ -match '^expenselog\.clientId=' } | Select-Object -First 1) -replace '^expenselog\.clientId=', ''
if (-not $ClientId) { Write-Host "Set expenselog.clientId in local.properties (see local.properties.example)." }
$Scope       = 'Files.ReadWrite offline_access'
$Auth        = 'https://login.microsoftonline.com/consumers/oauth2/v2.0'
$Graph       = 'https://graph.microsoft.com/v1.0'
$KeysDir     = 'D:\AndroidDev\keys'
$ReleasesDir = 'D:\AndroidDev\releases'
$TokenFile   = Join-Path $KeysDir 'onedrive-token.dat'   # refresh token, encrypted for this Windows user (DPAPI)

function Save-Token([string]$refresh) {
    New-Item -ItemType Directory -Force $KeysDir | Out-Null
    ConvertTo-SecureString $refresh -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $TokenFile -Encoding ascii
}

function Read-Token {
    $secure = Get-Content $TokenFile | ConvertTo-SecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

function Start-OneDriveSignIn {
    $dc = Invoke-RestMethod -Method Post -Uri "$Auth/devicecode" -Body @{ client_id = $ClientId; scope = $Scope }
    Write-Host "SIGN-IN: open $($dc.verification_uri) and enter the code $($dc.user_code)"
    $deadline = (Get-Date).AddSeconds([int]$dc.expires_in)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds ([int]$dc.interval + 1)
        try {
            $t = Invoke-RestMethod -Method Post -Uri "$Auth/token" -Body @{
                grant_type = 'urn:ietf:params:oauth:grant-type:device_code'; client_id = $ClientId; device_code = $dc.device_code
            }
            Save-Token $t.refresh_token
            Write-Host "SIGNED IN. This laptop can now publish updates."
            $global:LASTEXITCODE = 0; return
        } catch {
            $err = $null
            try { $err = ($_.ErrorDetails.Message | ConvertFrom-Json).error } catch {}
            if ($err -ne 'authorization_pending' -and $err -ne 'slow_down') { Write-Host "Sign-in failed: $err"; $global:LASTEXITCODE = 1; return }
        }
    }
    Write-Host "Sign-in timed out."; $global:LASTEXITCODE = 1
}

# Returns Graph auth headers, or $null (with a message) when the laptop is not signed in.
function Get-OneDriveHeaders {
    if (-not (Test-Path $TokenFile)) { Write-Host "Not signed in on this laptop yet. Run: .\publish.ps1 -SignIn"; return $null }
    try {
        $t = Invoke-RestMethod -Method Post -Uri "$Auth/token" -Body @{
            grant_type = 'refresh_token'; client_id = $ClientId; refresh_token = (Read-Token); scope = $Scope
        }
    } catch { Write-Host "Laptop sign-in has expired. Run: .\publish.ps1 -SignIn"; return $null }
    if ($t.refresh_token) { Save-Token $t.refresh_token }
    @{ Authorization = "Bearer $($t.access_token)" }
}

# health.json written by the phone, or $null if there is none yet.
function Get-Health($headers) {
    try { Invoke-RestMethod -Uri "$Graph/me/drive/root:/ExpenseLog/health.json:/content" -Headers $headers }
    catch { $null }
}

function Read-Release([string]$file) {
    $p = Join-Path $ReleasesDir $file
    if (Test-Path $p) { Get-Content $p -Raw | ConvertFrom-Json } else { $null }
}

function Write-Release([string]$file, $obj) {
    New-Item -ItemType Directory -Force $ReleasesDir | Out-Null
    $obj | ConvertTo-Json | Set-Content (Join-Path $ReleasesDir $file) -Encoding utf8
}
