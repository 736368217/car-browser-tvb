<#
    publish_r2.ps1 — 把 APK 发布到 Cloudflare R2（经 Worker 上传端点）。

    为什么走 Worker 而不是 R2 S3 端点：本机到 <account>.r2.cloudflarestorage.com
    的 TLS 被中间层打断（curl/python 都在握手阶段被拒），而自定义域名
    dl.736.de5.net 正常。Worker 用 R2 binding 读写，本机只需 PUT。

    用法：
        powershell -NoProfile -File tools\publish_r2.ps1 -Project car-browser -Version 6.17 `
                   -Apk dist\car-browser-6.17.apk -Notes "修复 xxx" -VersionCode 27

    产物（对象 key）：
        <project>/<apk-file>                     安装包
        <project>/latest.json                    客户端检查更新用的清单
        <project>/history/<version>.json         每个版本的清单副本（便于回滚）

    免费额度守卫：R2 免费存储 10 GB/月。本脚本默认占到 5 GB 就拒绝上传并打印
    清理提示；删掉旧版本对象后再次运行即自动恢复，不需要改任何开关。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Notes = '',
    [int]$VersionCode = 0,
    [string]$BaseUrl = 'https://dl.736.de5.net',
    [string]$TokenFile = '',
    [int64]$MaxBytes = 5368709120,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not $TokenFile) {
    $TokenFile = Join-Path ([Environment]::GetFolderPath('Desktop')) '认证\r2-upload-token.txt'
}

function Fail($msg) { Write-Host "FAIL: $msg" -ForegroundColor Red; exit 1 }
function Ok($msg) { Write-Host "OK:   $msg" -ForegroundColor Green }

function Get-Sha256Hex([string]$path) {
    $alg = [System.Security.Cryptography.SHA256]::Create()
    $fs = [System.IO.File]::OpenRead($path)
    try { $bytes = $alg.ComputeHash($fs) } finally { $fs.Dispose(); $alg.Dispose() }
    return (-join ($bytes | ForEach-Object { $_.ToString('x2') }))
}

if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK not found: $Apk" }
if (-not (Test-Path -LiteralPath $TokenFile)) { Fail "upload token missing: $TokenFile" }
$token = (Get-Content -LiteralPath $TokenFile -Raw -Encoding UTF8).Trim()
if (-not $token) { Fail "upload token is empty: $TokenFile" }

$apkItem = Get-Item -LiteralPath $Apk
$sha = Get-Sha256Hex $apkItem.FullName
$size = $apkItem.Length
$apkKey = "$Project/$($apkItem.Name)"

$manifest = [ordered]@{
    version     = $Version
    versionCode = $VersionCode
    apk         = $apkKey
    size        = $size
    sha256      = $sha
    publishedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    notes       = $Notes
}
$manifestJson = ($manifest | ConvertTo-Json -Depth 4)

Write-Host "project : $Project"
Write-Host "version : $Version (code $VersionCode)"
Write-Host "apk     : $apkKey  $size bytes"
Write-Host "sha256  : $sha"
Write-Host "base    : $BaseUrl"

if ($DryRun) {
    Write-Host "--- latest.json (dry run) ---"
    Write-Host $manifestJson
    exit 0
}

# ---------------------------------------------------------------- 配额守卫 --
$usageRaw = & curl.exe -sS --max-time 60 -H "X-Upload-Token: $token" "$BaseUrl/_ls" 2>&1 | Out-String
try { $usage = $usageRaw | ConvertFrom-Json } catch { Fail "usage query failed: $usageRaw" }
$used = [int64]0
foreach ($o in $usage.objects) { $used += [int64]$o.size }
$after = $used + $size
$limitMb = [math]::Round($MaxBytes / 1MB, 0)
$usedMb = [math]::Round($used / 1MB, 2)
$afterMb = [math]::Round($after / 1MB, 2)
$pct = [math]::Round(100.0 * $after / $MaxBytes, 1)
Write-Host "storage : used $usedMb MB, after upload $afterMb MB / $limitMb MB ($pct%)"
if ($usage.truncated) { Write-Host "warning : object list truncated at 1000 entries, usage is a lower bound" -ForegroundColor Yellow }
if ($after -gt $MaxBytes) {
    Fail "free-quota guard: $afterMb MB > $limitMb MB. Delete old objects (e.g. $Project/history/* and retired APKs) and rerun. Nothing was uploaded."
}

function UploadFile([string]$key, [string]$path, [string]$contentType) {
    $url = "$BaseUrl/$key"
    $curlArgs = @('-sS', '--max-time', '300', '-X', 'PUT',
        '-H', "X-Upload-Token: $token",
        '-H', "Content-Type: $contentType",
        '--data-binary', "@$path",
        '-w', "`n%{http_code}",
        $url)
    $out = & curl.exe @curlArgs 2>&1 | Out-String
    $code = ($out.Trim() -split "`n")[-1].Trim()
    if ($code -ne '200') { Fail "upload $key -> HTTP $code : $out" }
    Ok "uploaded $key"
}

UploadFile -key $apkKey -path $apkItem.FullName -contentType 'application/vnd.android.package-archive'

$tmp = Join-Path $env:TEMP ("latest-$Version.json")
Set-Content -LiteralPath $tmp -Value $manifestJson -Encoding UTF8 -NoNewline
UploadFile -key "$Project/latest.json" -path $tmp -contentType 'application/json; charset=utf-8'
UploadFile -key "$Project/history/$Version.json" -path $tmp -contentType 'application/json; charset=utf-8'
Remove-Item -LiteralPath $tmp -Force

$check = & curl.exe -sS --max-time 60 "$BaseUrl/$Project/latest.json" 2>&1 | Out-String
if ($check -notmatch [regex]::Escape($sha)) { Fail "readback: latest.json does not carry this sha256" }
Ok "readback latest.json"

$head = & curl.exe -sS -I --max-time 60 "$BaseUrl/$apkKey" 2>&1 | Out-String
if ($head -notmatch '200') { Fail "readback: APK not reachable : $head" }
Ok "readback $apkKey"

Write-Host ""
Write-Host "published: $BaseUrl/$Project/latest.json"
