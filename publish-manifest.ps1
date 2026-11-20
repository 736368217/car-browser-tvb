#requires -Version 5.1
<#
  历史归档：6.8 的旧更新清单生成器，已退出当前发布流程。

  旧域名已永久停用，不执行、不探测、不恢复其默认 DownloadBase。
  本脚本及 publish/ 仅保留旧格式示例，不代表线上 latest 状态。

  现役 R2 发布入口是 tools/publish_r2.ps1；用法与授权边界统一见 README.md。
  本文件保留的旧参数和输出逻辑仅用于历史追溯，不在知识整理中运行。

  当前包身份与遗留物清单见 VERIFY.md。
#>
param(
  [string]$Apk = '.\dist\car-browser-6.8.apk',
  [string]$Version = '',
  [int]$VersionCode = 0,
  [string]$DownloadBase = '',
  [string]$OutDir = '.\publish'
)

# 必须 Continue：文件哈希 / 字符串命令可能向 stderr 写正常信息，Stop 会误中断。
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = $PSScriptRoot
if (-not (Test-Path $Apk)) { throw "找不到 APK: $Apk" }
$apkItem = Get-Item $Apk
$sha = (Get-FileHash $apkItem.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$size = $apkItem.Length

if ([string]::IsNullOrWhiteSpace($Version)) {
  $m = [regex]::Match($apkItem.Name, 'car-browser-([0-9][0-9A-Za-z.]*)\.apk$')
  if (-not $m.Success) { throw 'APK 文件名里读不出版本，请用 -Version 指定' }
  $Version = $m.Groups[1].Value
}
$manifestText = Get-Content (Join-Path $root 'AndroidManifest.xml') -Raw -Encoding UTF8
if ($VersionCode -le 0) {
  $vm = [regex]::Match($manifestText, 'android:versionCode="(\d+)"')
  if ($vm.Success) { $VersionCode = [int]$vm.Groups[1].Value }
}
$manifestVersion = [regex]::Match($manifestText, 'android:versionName="([^"]+)"')
if (-not $manifestVersion.Success) { throw 'AndroidManifest.xml 缺 versionName' }
if ($Version -ne $manifestVersion.Groups[1].Value) {
  throw "APK 版本 $Version 与 AndroidManifest.xml 的 $($manifestVersion.Groups[1].Value) 不一致"
}

if ([string]::IsNullOrWhiteSpace($DownloadBase)) {
  $DownloadBase = 'https://dl.z736.vip/car-browser-tvb/' + $Version
}
$downloadBase = $DownloadBase.TrimEnd('/')
$apkName = $apkItem.Name
$downloadUrl = $downloadBase + '/' + $apkName

$out = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $root $OutDir }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$latest = [ordered]@{
  software      = 'car-browser-tvb'
  packageName   = 'com.carweb.tvb'
  version       = $Version
  versionCode   = $VersionCode
  versionName   = $Version
  releasedAt    = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
  files         = @(
    [ordered]@{
      name   = $apkName
      type   = 'apk'
      size   = $size
      sha256 = $sha
      url    = $downloadUrl
    }
  )
  url           = $downloadUrl
  sha256        = $sha
  size          = $size
}
$json = $latest | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText((Join-Path $out 'latest.json'), $json + "`n", (New-Object System.Text.UTF8Encoding $false))

$versionsPath = Join-Path $out 'versions.json'
$versions = @($Version)
if (Test-Path $versionsPath) {
  try {
    $old = Get-Content $versionsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($old -and $old.versions) {
      foreach ($v in $old.versions) {
        if ($v -and $v -ne $Version -and $versions.Count -lt 2) { $versions += [string]$v }
      }
    }
  } catch { }
}
$versionsObj = [ordered]@{
  software = 'car-browser-tvb'
  versions = $versions
}
[System.IO.File]::WriteAllText($versionsPath,
  ($versionsObj | ConvertTo-Json -Depth 4) + "`n", (New-Object System.Text.UTF8Encoding $false))
[System.IO.File]::WriteAllText((Join-Path $out ($apkName + '.sha256')),
  "$sha  $apkName`n", (New-Object System.Text.UTF8Encoding $false))

Write-Host "VERSION     = $Version ($VersionCode)"
Write-Host "APK_BYTES   = $size"
Write-Host "SHA256      = $($sha.ToUpperInvariant())"
Write-Host "URL         = $downloadUrl"
Write-Host "MANIFEST    = $(Join-Path $out 'latest.json')"
Write-Host "VERSIONS    = $($versions -join ', ')"
Write-Host ''
Write-Host '把 publish/latest.json、publish/versions.json 与 APK 一起部署到可信发布目录；'
Write-Host 'GitHub Release 资产仍只放 APK，不要挂清单。'
exit 0
