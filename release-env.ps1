# Dot-sourced by build.ps1 and watchdog.ps1: sets the build toolchain and the release key password
# (decrypted from D:\AndroidDev\keys\release-pass.dat, readable only by this Windows user).
$env:JAVA_HOME = (Get-ChildItem 'D:\AndroidDev\jdk' -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = 'D:\AndroidDev\sdk'
$Gradle = (Get-ChildItem 'D:\AndroidDev\gradle' -Directory | Select-Object -First 1).FullName + '\bin\gradle.bat'
$passFile = 'D:\AndroidDev\keys\release-pass.dat'
if (Test-Path $passFile) {
    $secure = Get-Content $passFile | ConvertTo-SecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
# Where the signed release APK lands.
$ReleaseApkRel = 'app\build\outputs\apk\release\app-release.apk'
