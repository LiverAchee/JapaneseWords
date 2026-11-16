# build-apk.bat 打包成功后调用：把 APK 按版本号存到 dist\（保留历史版本）。
# 本项目体积大，不往手机同步文件夹里放 APK。
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$gradle = Join-Path $root 'android\app\build.gradle'
$version = (Select-String -Path $gradle -Pattern "versionName '([^']+)'").Matches[0].Groups[1].Value
$apk = Join-Path $root 'android\app\build\outputs\apk\release\app-release.apk'
$name = "JapaneseWords-$version.apk"

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $apk (Join-Path $dist $name) -Force

Write-Host ''
Write-Host "Done: dist\$name"
