#requires -Version 5.1
<#
  车机浏览器 B — APK 构建

  这个仓库是自包含的：源码 + assets + 构建脚本都在里面，clone 下来就能出包。

  为什么不用 Gradle：
    主代码是零第三方依赖的纯 Java（只有 JUnit 是测试期依赖，本脚本不跑测试），
    所以 aapt2 + javac + d8 + apksigner 四步就够，比拉 Gradle + AGP + 依赖树快一个数量级。
    本机也确实没有 gradle 命令。

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Clean
#>
param(
  [switch]$Clean
)

# 注意：这里必须是 Continue 而不是 Stop。
# keytool / javac 会把正常信息写到 stderr，Stop 模式下 PowerShell 会把它当成
# terminating error 直接中断构建（keytool 明明已经生成了证书，脚本却停在那一步）。
# 真正要中断的地方一律显式 throw，每一步也用 $LASTEXITCODE 自行判定。
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk -or -not (Test-Path $sdk)) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }

$buildTools = Join-Path $sdk 'build-tools\34.0.0'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$d8 = Join-Path $sdk 'cmdline-tools\latest\bin\d8.bat'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'

foreach ($p in @($aapt2, $zipalign, $apksigner, $d8, $androidJar)) {
  if (-not (Test-Path $p)) { throw "缺少构建工具: $p" }
}

$work = Join-Path $root 'build'
$dist = Join-Path $root 'dist'
$gen = Join-Path $work 'gen'
$classes = Join-Path $work 'classes'
$dexDir = Join-Path $work 'dex'

if ($Clean -and (Test-Path $work)) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work, $gen, $classes, $dexDir, $dist | Out-Null
Get-ChildItem $gen -Recurse -File -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem $classes -Recurse -File -ErrorAction SilentlyContinue | Remove-Item -Force

function Invoke-Step {
  param([string]$Title, [scriptblock]$Body)
  Write-Host "== $Title"
  & $Body
  if ($LASTEXITCODE -ne 0) { throw "步骤失败: $Title (exit $LASTEXITCODE)" }
}

$manifest = Join-Path $root 'AndroidManifest.xml'
$resDir = Join-Path $root 'res'
$assetsDir = Join-Path $root 'assets'
$srcDir = Join-Path $root 'src'
if (-not (Test-Path $manifest)) { throw '缺 AndroidManifest.xml' }
if (-not (Test-Path $resDir)) { throw '缺 res 目录' }

$txt = Get-Content $manifest -Raw -Encoding UTF8
$verName = '1.0'
$verCode = 1
$m = [regex]::Match($txt, 'android:versionName="([^"]+)"')
if ($m.Success) { $verName = $m.Groups[1].Value }
$m2 = [regex]::Match($txt, 'android:versionCode="(\d+)"')
if ($m2.Success) { $verCode = [int]$m2.Groups[1].Value }
$pkgMatch = [regex]::Match($txt, 'package="([^"]+)"')
if (-not $pkgMatch.Success) { throw 'manifest 里没有 package 属性' }

Write-Host "包名 : $($pkgMatch.Groups[1].Value)"
Write-Host "版本 : $verName ($verCode)"
Write-Host ''

# ---------- 1. 资源 ----------
$resZip = Join-Path $work 'res.zip'
if (Test-Path $resZip) { Remove-Item $resZip -Force }
Invoke-Step 'aapt2 compile' { & $aapt2 compile --dir $resDir -o $resZip }

# ---------- 2. 链接（APK 骨架 + R.java） ----------
$baseApk = Join-Path $work 'base.apk'
if (Test-Path $baseApk) { Remove-Item $baseApk -Force }
$linkArgs = @(
  'link', '-o', $baseApk,
  '-I', $androidJar,
  '--manifest', $manifest,
  '-R', $resZip,
  '--java', $gen,
  '--auto-add-overlay',
  '--min-sdk-version', '21',
  '--target-sdk-version', '33',
  '--version-code', "$verCode",
  '--version-name', "$verName"
)
if (Test-Path $assetsDir) { $linkArgs += @('-A', $assetsDir) }
Invoke-Step 'aapt2 link' { & $aapt2 @linkArgs }

# ---------- 3. 编译 Java ----------
$javaFiles = @()
$javaFiles += Get-ChildItem $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem $gen -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($javaFiles.Count -eq 0) { throw '没有 java 源文件' }

$argFile = Join-Path $work 'javac-sources.txt'
[System.IO.File]::WriteAllLines($argFile, [string[]]$javaFiles, (New-Object System.Text.UTF8Encoding $false))

Invoke-Step 'javac' {
  & javac --release 17 -encoding UTF-8 -nowarn -classpath $androidJar -d $classes "@$argFile"
}

# ---------- 4. dex ----------
$classesJar = Join-Path $work 'classes.jar'
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
& jar --create --file $classesJar -C $classes . 2>$null
if ($LASTEXITCODE -ne 0) { throw 'jar 打包失败' }

Get-ChildItem $dexDir -Filter *.dex -ErrorAction SilentlyContinue | Remove-Item -Force
Invoke-Step 'd8' {
  & $d8 --lib $androidJar --min-api 21 --output $dexDir $classesJar
}

# ---------- 5. classes.dex 塞进 APK ----------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dexFile = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dexFile)) { throw 'd8 没产出 classes.dex' }
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
try {
  $old = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
  if ($old) { $old.Delete() }
  [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, $dexFile, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
} finally {
  $zip.Dispose()
}

# ---------- 6. 对齐 + 签名 ----------
$aligned = Join-Path $work 'aligned.apk'
if (Test-Path $aligned) { Remove-Item $aligned -Force }
Invoke-Step 'zipalign' { & $zipalign -p -f 4 $baseApk $aligned }

# 签名证书不进仓库（私钥不该有第二个副本）。
# 想换自己的证书：设环境变量 CARWEB_KEYSTORE 指向 .jks，或把证书放到 keystore/carweb.jks。
$ks = $env:CARWEB_KEYSTORE
if (-not $ks) { $ks = Join-Path $root 'keystore\carweb.jks' }
$ksPass = if ($env:CARWEB_KEYSTORE_PASS) { $env:CARWEB_KEYSTORE_PASS } else { 'carweb2026' }
$ksAlias = if ($env:CARWEB_KEYSTORE_ALIAS) { $env:CARWEB_KEYSTORE_ALIAS } else { 'carweb' }

if (-not (Test-Path $ks)) {
  New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
  Write-Host "== 生成签名证书 -> $ks"
  & keytool -genkeypair -v -keystore $ks -alias $ksAlias -keyalg RSA -keysize 2048 `
    -validity 10000 -storepass $ksPass -keypass $ksPass `
    -dname 'CN=CarWeb, OU=Car, O=Local, L=Local, ST=Local, C=CN' 2>$null
  if ($LASTEXITCODE -ne 0) { throw '生成证书失败' }
}

$outApk = Join-Path $dist "car-browser-$verName.apk"
if (Test-Path $outApk) { Remove-Item $outApk -Force }
Invoke-Step 'apksigner' {
  & $apksigner sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --ks-key-alias $ksAlias --v1-signing-enabled true --v2-signing-enabled true `
    --v3-signing-enabled true --out $outApk $aligned
}

$size = [math]::Round((Get-Item $outApk).Length / 1KB, 1)
$sha = (Get-FileHash $outApk -Algorithm SHA256).Hash
Write-Host ''
Write-Host "APK : $outApk  ($size KB)"
Write-Host "SHA : $sha"
exit 0
