# Called by build-apk.bat when android\keystore.properties is missing: creates the signing key with a random password.
# Neither file goes into git (see .gitignore). Back them up: without them new builds cannot upgrade an installed copy.
$ErrorActionPreference = 'Stop'
$android = Join-Path $PSScriptRoot 'android'
$jks = Join-Path $android 'keystore\release.jks'
$props = Join-Path $android 'keystore.properties'
if (Test-Path $jks) {
    throw "$jks exists but keystore.properties is missing; create it by hand (storeFile / storePassword / keyAlias / keyPassword)."
}
New-Item -ItemType Directory -Force (Split-Path $jks) | Out-Null
$pass = [guid]::NewGuid().ToString('N')
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
& $keytool -genkeypair -keystore $jks -storepass $pass -keypass $pass -alias japanesewords `
    -keyalg RSA -keysize 2048 -validity 36500 -dname 'CN=JapaneseWords'
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
@"
storeFile=keystore/release.jks
storePassword=$pass
keyAlias=japanesewords
keyPassword=$pass
"@ | Set-Content -Encoding ascii $props
Write-Host 'Created android\keystore\release.jks and android\keystore.properties - back them up.'
