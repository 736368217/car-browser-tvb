# 车览（CHELAN）

面向零跑 B01 触摸车机的 Android WebView 浏览器，目标显示环境为 2880×1620、160dpi、横屏。基于 `viyiviyi/tv-browser` 改造；项目来源与既有许可限制见 [NOTICE.md](NOTICE.md)。

## 当前状态

截至 **2026-11-21（UTC）**，R2 主通道与 GitHub Release 均提供 **6.23（versionCode 33）**，包含菜单、子页、全屏恢复和按钮反馈的克制动效，以及系统动画关闭时的即时终态。完整 SHA、大小与验收范围见 [VERIFY.md](VERIFY.md)。

零跑 B01 Android 12L/API 32 真车验收待补，已完成的图标与页面验收在 MuMu Android 15/API 35 上执行。

- [6.23 动效优化与验收报告](reports/motion-6.23.html)：内嵌 7 张原生画面，包含页面回归、逐帧动效和系统关闭动画检查。
- [6.22 图标效果与验收报告](reports/lucide-6.22.html)：内嵌本轮截图和原生图标总览。
- [6.21 下载恢复报告](reports/download-recovery-6.21.html)：历史专项证据，不充当 6.22 的重测结果。
- [6.20 全量验收报告](reports/main-full-20261120.html)：历史版本报告，按其实际安装 SHA 解读。
- [项目执行约束](AGENTS.md)：给后续 Agent 的最小入口。

## 使用

| 功能 | 当前行为 |
|---|---|
| 导航 | 地址栏兼作搜索框；后退、前进、主页、标签页在工具栏。主页保留其他标签，最多同时保留 5 个标签。 |
| 返回与全屏 | 功能页可返回父页；主页根部只提示，不因一次返回退出。没有页面级浮动返回按钮；全屏隐藏工具栏，右上角“退出全屏”手柄恢复工具栏。菜单/子页/工具栏恢复带短动效，跟随系统动画设置；关闭系统动画时直接到达终态。退出应用用菜单“退出”。
| 首页与收藏 | 7 列站点网格，品牌 Logo 随包内置；长按进入拖动排序/删除编辑。搜索引擎在设置中选择。 |
| 外部网页 | 桌面/手机版 UA 切换、页内查找、阅读、夜间/无图模式、广告过滤、多窗口标签和视频全屏。触摸按住约 400ms 可触发鼠标悬停。 |
| 下载 | 系统下载目录（Android 10+ MediaStore）、应用私有目录、自选文件夹（SAF）；支持进度、取消、失败重试、打开、分享、删除文件和仅删记录。清空记录不会删除完成文件。 |
| 文件打开 | 图片/文本可在应用内预览，其他类型交给系统应用；APK 交给系统安装器。旧私有路径或 MediaStore URI 失效时尝试重定位，确实缺失时改为可重试状态；SAF 仍需有效授权。 |
| 图标 | 操作图标使用离线 Lucide 原生矢量；站点 Logo 与应用启动图标保留品牌标识。 |

渲染内核由设备的 Android System WebView 提供，本项目不打包浏览器内核。目标车机上的系统权限和安装限制须以真车实测为准。

## 构建

运行时是 Java + Android 框架，不需要 Gradle。构建要求 **JDK 17+、Android SDK Build Tools 35.0.0、platforms/android-35**；脚本固定 compile SDK 35，APK 为 minSdk 21 / targetSdk 33。

在仓库根目录执行：

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
[xml]$manifest = Get-Content .\AndroidManifest.xml -Raw -Encoding UTF8
$version = [string]$manifest.manifest.versionName
Get-FileHash -Algorithm SHA256 ".\dist\car-browser-$version.apk"
```

SDK 不在上述位置时，将 `ANDROID_HOME` 指向实际 SDK 根目录；未设置时生产构建脚本也会查找 `%LOCALAPPDATA%\Android\Sdk`。

- 版本号需同时修改 [AndroidManifest.xml](AndroidManifest.xml) 与 [BuildConfig.java](src/com/carweb/tvb/BuildConfig.java)，否则安装身份和关于页/更新比较会不一致。
- 签名使用原证书；支持 `CARWEB_KEYSTORE`、`CARWEB_KEYSTORE_PASS`、`CARWEB_KEYSTORE_ALIAS`。脚本缺证书时会生成新证书，但新证书不能冒充原签名用于覆盖升级。私钥不进 Git。
- `build.ps1` 含中文，Windows PowerShell 5.1 下须保留 UTF-8 BOM。脚本逐步检查 `$LASTEXITCODE`；javac 的正常 stderr 提示不等于构建失败。
- 构建会覆盖同名 `dist` 产物。重建包不保证整包字节级复现；发布前保存并验收将要上传的确切 SHA，不要用另一次重建包替换它。

### 图标维护

官方版本、40 个 SVG 及生成资源的摘要见 [Lucide 来源清单](third_party/lucide/source.json)，完整许可见 [Lucide LICENSE](third_party/lucide/LICENSE)；APK 内也带 `assets/licenses/lucide.txt`。

```powershell
python -B .\tools\import_lucide.py
```

该命令只用仓库内 SVG 重新生成原生矢量，不联网。只有有意更新固定的官方源码版本时才使用 `--fetch`，并一同提交 SVG、资源、来源清单及许可。圆弧标志需要分隔以兼容 Android PathParser；整套图标使用统一安全边距，不能靠单独缩放某几个图标掩盖裁切。

## 验收

1. 本地脚本检查可执行 `node --check .\assets\home.js`；原生审计构建需要 Python 3.9+。审计脚本保留了机器专用 SDK 默认值，跨工作区**显式设置 `ANDROID_HOME`**，不要依赖该默认值。
2. 在 PI-Desktop 中把输出放入会话 scratch，再构建独立 instrumentation APK：

   ```powershell
   $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
   $auditOut = Join-Path $env:PI_SCRATCH_DIR 'car-browser-audit'
   python -B .\tests\native\build_audit.py $auditOut
   ```

   其他环境由调用者指定等价的临时输出目录。当前审计构建器固定使用 `keystore/carweb.jks`，不读取生产构建的 `CARWEB_KEYSTORE*` 覆盖项；自定义签名场景要先核对两包签名，不得为安装审计包卸载主应用。
3. MuMu 操作遵守上级规则：全局实例池独占租约、每次操作前 `check`、全程静音、设备操作走 MuMu 插件。先验证实例级 2880×1620 @160dpi 和实际显示几何，禁止用 `wm size` 伪造车机档。备份偏好，结束恢复并释放自己的租约。
4. [6.22 图标计划](tests/native/tour-icons-622.json) 是当前图标/页面专项。其他 `tour-*.json` 中有固定旧版本预期，尤其 `tour-final.json`、`tour-core-620.json`、`tour-update-live.json`，选用前须核对，不能直接当作当前版全量通过。
5. 网页/下载 fixture 使用 [fixture_server.py](tests/native/fixture_server.py) 的 8099 端口，来宾访问 `http://10.0.2.2:8099`。更新下载测试须把 `CARWEB_AUDIT_APK` 指向本轮 APK，脚本默认仍是历史 6.20 文件。fixture 必须由可靠后台任务托管，结束后停止并核对端口；不要把服务退出导致的拒绝连接当成产品缺陷。
6. 每批核对 guest 安装 `base.apk` SHA、`result.json`、业务检查、原生节点/截图与 crash buffer。`instrument_rc=0`、安装成功或 PID 存活均不能单独算通过。原始证据按版本与 SHA 归档，不能改历史 SHA 充当新结果。

`smoke/` 默认被忽略，仅显式归档需共享的本轮证据及摘要；不要批量解除忽略，也不要提交 APK、私钥、偏好备份或原始凭据。

## 更新与发布

主清单为 `https://dl.736.de5.net/car-browser/latest.json`，对象为 `car-browser/car-browser-<版本>.apk`；手动下载页为同域名根路径。更新器按 **R2 清单 → GitHub API → Release 页面** 顺序读取；R2 连接超时 4 秒、读取超时 6 秒。R2 清单要求有效版本、项目路径、大小和 SHA；GitHub 路径在没有摘要时不能声称完成哈希校验。

应用更新使用 DownloadManager，校验后交给系统安装器，并受设备的安装授权限制。它不会绕过 OEM 策略；所有来源均不可达时显示失败。缺失或较旧的 GitHub Release 不等于与 R2 等价的兜底版本。

按项目发布约定，更新通过相关验证后提交推送，并完成 R2 和 GitHub 发布收口。确认构建提交可在远端读取后，用已验收的 APK 执行 R2 发布：

```powershell
[xml]$manifest = Get-Content .\AndroidManifest.xml -Raw -Encoding UTF8
$version = [string]$manifest.manifest.versionName
$versionCode = [int]$manifest.manifest.versionCode
$apk = Join-Path '.\dist' "car-browser-$version.apk"
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\publish_r2.ps1 `
    -Project car-browser -Version $version -VersionCode $versionCode -Apk $apk `
    -Notes '填写本次用户可见更新说明'
```

R2 脚本经 Worker 上传 APK、`latest.json` 和 `history/<版本>.json`，可用 `-TokenFile` 指定本机令牌文件，默认占用守卫为 5 GiB。GitHub 兜底由 `gh release` 单独维护。先查目标版本的 Release 和资产，补齐缺失资产时使用与 R2 相同的 APK 和用户可见说明，标签指向已推送的构建提交。已有资产与验收 SHA 一致时直接回读核对。

分别检查 R2 清单与 GitHub 公开 latest 的版本，下载两个通道的完整 APK，与验收包比对大小和 SHA-256。设备在线检查更新另行记录。

[Worker 源码](tools/r2-worker.mjs) 与 [部署工具](tools/deploy_worker.py) 用于服务维护，普通 APK 发布不需要重新部署 Worker。旧 `dl.z736.vip` 通道已永久停用，不探测、不恢复；根目录 `publish-manifest.ps1` 和 `publish/` 是 6.8 旧格式遗留，不能作为现役发布入口。

## 目录与资料边界

| 位置 | 用途 |
|---|---|
| `src/com/carweb/tvb/` | 原生界面、WebView、下载中心/Provider、更新器、站点存储与桥接。 |
| `assets/`、`res/` | 首页/注入脚本、品牌图、Lucide 原生矢量和随包许可。 |
| `tools/`、`tests/native/` | 图标导入、R2 发布/维护、独立原生审计与 fixture。 |
| `reports/`、`smoke/` | 按版本保存的报告和选择归档的原始证据；不把旧报告视作当前版门禁。 |
| `build/`、`dist/`、`keystore/` | 本机生成物与签名材料，不进 Git。旧 APK 文件名不能证明它是当年的验收/发布包。 |
| [upstream-README.md](upstream-README.md) | 上游原始说明快照，只作溯源。其 Gradle、`tools/setup-toolchain.ps1`、TV 布局、返回键行为和相对链接不适用于本工程。 |

详细历史和旧版本取舍见 [VERIFY.md](VERIFY.md) 的历史索引及 Git，不在 README 重复维护另一份逐版流水账。
