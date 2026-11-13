# 实机验收记录

## 2026-11-14 · v6.14（versionCode 24）下载中心

- 构建：`dist/car-browser-6.14.apk`（702.6 KB）；SHA-256 `6E807C5316FD1F1E0204484EB5E467AAA89EA3A089A98F8F733A380D5F427F6B`
- 设备：MuMu vm0，Android 15，custom 2880×1620 @160dpi；本地 HTTP 服务 `10.0.2.2:8099`（强制 `Content-Disposition: attachment`，确保走下载而不是内联渲染）
- 验证范围：目录切换、真实进度、取消、失败与重试、打开文件、打开目录、私有目录 Provider、SAF 选择器

| 项目 | 结果 | 证据 |
|---|---|---|
| 默认目录（系统下载） | `shot.png` 落到 `/sdcard/Download/shot.png`（6,511 B），MediaStore 行 `_display_name=shot.png` | `ls -l` + `content query` + 日志 `开始下载 shot.png -> 系统下载目录（Download）` |
| 列表呈现 | `下载 · N`、保存位置行（可“更改”）、每条文件名 + 状态（如 `已完成 · 6 KB`）+ 右侧动作（打开/取消/重试） | fresh UI 树 + `ux614-list.png` |
| 打开文件 | 点“打开”进入系统“打开方式”（图库 / CX文件管理器 / 文件启动器），返回后回到应用 | fresh UI 树（ResolverActivity）+ `ux614-opened.png` |
| 打开目录 | 系统目录：列出“文件 / CX文件管理器 / 文件启动器”供选择 | fresh UI 树 + `ux614-folder-public.png` |
| 切到私有目录 | 下载 `note.txt` → `/storage/emulated/0/Android/data/com.carweb.tvb/files/Download/note.txt`（26 B） | 日志 `-> 应用内私有目录` + `ls -l` |
| 私有目录打开 | `LocalFileProvider` 授权后可被其它应用读取；外壳直接访问被拒（`SecurityException ... not exported from UID 10079`） | ResolverActivity 列表 + adb `content read` 拒绝证据 |
| 取消 | 20 MB 慢速下载中点“取消”，`/sdcard/Download` 无 slow 残留 | `ls | grep slow` 无结果 + `ux614-progress.png` / `ux614-canceled.png` |
| 失败与重试 | 404 地址显示 `失败：服务器返回 HTTP 404`，右侧动作变为“重试” | fresh UI 树 + `ux614-fail.png` |
| 自选文件夹 | “更改 → 选择其他文件夹…”打开系统 DocumentsUI（`com.android.documentsui/.picker.PickActivity`） | `dumpsys activity` + `ux614-saf.png` |
| 长按菜单 | 打开文件 / 分享文件 / 打开所在目录 / 复制文件路径 / 删除记录和文件 | fresh UI 树 + `ux614-menu.png` |
| 私有目录定位说明 | 私有目录点“打开目录”不再静默失败：复制路径并提示系统限制、建议改系统下载目录 | 代码 + `ux614-folder-private` 截图 |

### 边界

- 未在真车验证；MuMu 上系统“下载”目录对应 `/sdcard/Download`，真车若被 OEM 改路径以实际为准。
- 自选文件夹的**写入**未在本轮打勾（只验证到选择器打开与授权流程）；Android 11+ 对 `Android/data` 的访问限制不影响 SAF 其它目录。
- UiAutomator 仍会“先写出 XML 再 SIGSEGV”，本轮同样按一次性 dump + 宿主解析使用。

## 2026-11-13 · v6.13（versionCode 23）自动更新

- 构建：`dist/car-browser-6.13.apk`（707,188 B）；SHA-256 `8DC9FD0F39336F1E039B95CC01F4DDF5BDD076E4BFF4D7296316F1A003567396`
- 发布：GitHub Release `v6.13` 的资产**只有** `car-browser-6.13.apk`（707,188 B）；API `digest` 与本地 SHA 完全一致
- 仓库可见性：转为公开后匿名 `api.github.com` 返回 200（此前私有库为 404）；APK 内不含任何 token
- 设备：MuMu vm0，Android 15，custom 2880×1620 @160dpi

| 项目 | 结果 | 证据 |
|---|---|---|
| 发现新版本 | 装 6.12 后检查：`发现新版本 6.13`，副行 `car-browser-6.13.apk · 0.7 MB · SHA 8dc9fd0f3933`，操作按钮为「下载并安装」 | 本轮 fresh UI 树 |
| 已是最新 | 装 6.13 后检查：`已是最新 6.13`，同样显示资产名、大小与 SHA 前 12 位 | 本轮 fresh UI 树 |
| 下载 | 点「重新下载安装」后应用私有目录出现 707,188 B 的 `car-browser-6.13.apk` | guest `ls -l` |
| 校验 | 下载文件 sha256 与 API `digest` 一致；logcat `更新包已交给安装器 sha=8dc9fd0f3933` | `toybox sha256sum` + `TvBrowser` 日志 |
| 安装器 | 系统安装器弹出「要更新此应用吗？当前数据不会丢失。」；点「更新」后 `versionName=6.13 / versionCode=23`，应用进程结束，crash buffer 无 `FATAL EXCEPTION` | 安装器 fresh UI 树 + `dumpsys package` |
| 限流兜底 | 匿名配额耗尽时（HTTP 403，`X-RateLimit-Remaining: 0`）自动回落发布页，仍显示 `已是最新 6.13 / car-browser-6.13.apk · 0.7 MB` | 宿主复现 403 响应头 + 设备端 fresh UI 树 |
| 网络不可达 | 访客未配代理直连 GitHub：`检查失败`，原因 `connection closed；发布页兜底也失败：connection closed` | 本轮 fresh UI 树 |

### 自动更新的网络前提

- MuMu 访客未配代理时，直连 `api.github.com:443` 的 TLS 会被重置（TCP 可达，应用侧表现为 `connection closed`）。测试时用宿主临时 TCP 转发 + 访客 `http_proxy` 打通，测完已撤销（`settings put global http_proxy :0`）。
- 车机端同理：网络必须能访问 GitHub（或经同类代理），否则只显示上述可读失败原因，不伪造成功。
- API 路径校验 sha256 与大小；发布页兜底路径拿不到 digest，只按资产名探测，不校验哈希。

### UiAutomator 现状（本轮定性）

- `uiautomator dump` 在这版 MuMu Android 15 镜像上，无论走 MuMuManager `sh`、`nsenter` 包装还是插件 `adb` 通道，都是**先写出完整 XML，再以 139/SIGSEGV（偶发 Bus error）退出**；XML 可解析，问题在工具退出路径而非应用。
- 因此按「同一动作只 dump 一次 → 宿主解析校验」使用：删旧文件、dump、确认 XML 可解析且含目标包，再取文案。连续多次 dump 期间实例会停，需用插件重启（直接因果未证实）。
- 界面视觉证据仍以 2880×1620 截图为准；本轮更新流程的文案证据来自上述一次性 UI 树。

## 2026-11-12 · v6.9（versionCode 19）

- 构建：`dist/car-browser-6.9.apk`（698,996 B）；SHA-256：`F5583005345E351571CAE6CED789A90B2114A3ADF23339F78751E3753E8108B4`
- 身份：`com.carweb.tvb` / `6.9 (19)`；`zipalign -c -v 4` 与 `apksigner verify` 通过，v1/v2/v3 签名有效。
- 设备：MuMu vm0，Android 15；实例配置 `custom 2880×1620 @160dpi`，`system_volume_close=true`。
- 几何：`init=1620x2880 160dpi noscale`；`cur=2880x1620 app=2880x1620`；输入 viewport 的 logical/physical frame 均为 `[0,0,2880,1620]`。
- 安装和启动通过 MuMu 插件完成。应用在可连接时位于前台；应用 crash buffer 未见 `FATAL EXCEPTION`。MuMu 实例曾在重复 UiAutomator dump 后停止，重新启动后应用 PID 改变，故不将全程描述为单一 PID 稳定。

| 项目 | 结果 | 证据 |
|---|---|---|
| 首页与触摸格 | 7 列网格；首格 bounds `[410,549][640,833]`，站点格宽 230px | 本轮 fresh UI 树 |
| 功能面板 | 菜单打开，设置入口可触达；面板显示期间悬浮返回隐藏 | 本轮 fresh UI 树 |
| 应用内返回 | 设置页开关为 on；触摸后出现节点 `[2696,1272][2828,1404]`，空闲后隐藏；点按返回首屏 | fresh UI 树及前后台节点对照 |
| 偏好持久化 | 关闭开关后 `ui_state.xml` 写入 `false`；guest 重启后值仍为 `false`；随后恢复为 `true`，隐藏时间仍为 4 秒 | guest SharedPreferences 读回 |
| 标签保留 | 回首屏后标签页显示 `标签页 · 2`，仍保留 `https://www.baidu.com/` | 本轮 fresh UI 树 |
| 全屏 | 工具栏隐藏；恢复手柄 bounds `[2759,35][2845,121]`；点按后工具栏控件重新出现 | 全屏前后 fresh UI 树 |
| 功能页第二页 | 左滑后箭头变「上一页」；10 项：页内查找 / 阅读模式 / 夜间模式 / 无图模式 / 电脑模式 / 清除缓存 / 广告过滤 / 清除数据 / 手动更新 / 关闭标签 | fresh UI 树 + `ux69b-sheet-p2.png` |
| 设置子页返回父页 | 设置 → 搜索引擎 → 返回：`settings` 与 `settings-return` 截图逐字节相同 | 两图 SHA-256 均为 `98E7F117…AC949B8` |
| 下载 | 地址栏输入 `http://10.0.2.2:8099/test.zip`：应用私有目录出现 26 字节 `test.zip`，内容与源文件逐字节一致；应用内列表显示 `test.zip / 已完成` | guest `od -c` + 下载页 fresh UI 树 + `ux69b-downloads.png` |

### 截图与像素证据

七张 PNG，全部 2880×1620，存于 `car-browser/smoke/ux69b-*.png`：`home`、`sheet-p1`、`sheet-p2`、`settings`、`engine`、`settings-return`、`downloads`。

- 首屏列扫描（y=690）：7 列，列间距 305px，logo 墨迹宽 182px，左右边距各 434px，网格居中。
- 功能页翻页：高亮圆点在 p1 位于 `x=1413..1432`，p2 移至 `x=1447..1466`（p1 该位置为暗点）。
- `settings` 与 `settings-return` 的 SHA-256 相同（`98E7F117879A5399BD88E1E56AD3C73D4B226FDCA6C3579896898C826AC949B8`），`engine` 为另一张（`A1B06E9A…FA479393`）。

### 边界

- UiAutomator 通过 system_server namespace 可写出 fresh XML，但随后报 `Segmentation fault`；重复采集期间 MuMu 实例多次停止，直接因果未确认。截图与 UI 树分别记录。
- 截图只做尺寸与像素几何量化，未做 OCR；界面文案以上述同轮 UI 树为准。
- 未连接零跑 B01 真车；MuMu 证据不替代真车验收。

## 2026-11-06 · v6.8（versionCode 18）
> 历史记录仅适用于 v6.8；其中的清单源已在 v6.9 退役，不能作为当前版本更新入口。

- 构建：`dist/car-browser-6.8.apk`（698,996 B）
- SHA256：`2A80419AF8429D315BE7D725ACC1C301AC28EB2A543B90C4D00D627DCBDF6176`
- 设备：MuMu 车机档，2880×1620 @160dpi（`wm size reset; wm size 2880x1620; wm density 160`）
- 安装：`adb install -r` Success；冷启动 `versionName=6.8 / versionCode=18`，无 `FATAL EXCEPTION`
- 自动更新清单：`publish/latest.json`、`publish/versions.json` 与 `.sha256` sidecar 由 `publish-manifest.ps1` 生成；GitHub Release 资产只挂 APK

| 项目 | 操作 | 结果 | 截图 |
|---|---|---|---|
| 首屏 | 冷启动 | 13 个站点图标、两行网格、地址栏正常 | `t68-00.png` |
| 功能页第 1 页 | 点右上菜单 | 10 项两行、圆点状态正确 | `t68-01-sheet1.png` |
| 功能页第 2 页 | 左滑 | 10 项两行、设置/关于入口可见 | `t68-02-sheet2.png` |
| 关于 / 更新卡片 | 设置/功能页进关于 | 版本 6.8，「版本更新」卡片、三按钮与说明完整 | `t68-03-settings.png`、`t68-08-polished-about.png` |
| 发布源 404 自救 | 真实公网清单未上线 | 「检查失败：发布源尚未上线（HTTP 404）」+ 原因、重试、发布页兜底 | `t68-08-polished-about.png` |
| 打开下载页 | 点「打开发布页」 | 前台切到系统浏览器 Activity（MuMu 有 Chromium），无崩溃 | `t68-11-feed-open.png` / `t68-14-feed-open-https.png` |
| TLS 失败分支 | 本地 HTTPS 桩、自签 CA | 明确显示 `CertPathValidatorException`，卡片仍可重试/打开发布页 | `t68-12-feed-success-https.png` |
| 清单格式校验 | 本地 `publish/latest.json` | `software/packageName/version/versionCode/size/sha256/url` 齐全；脚本读回断言通过 | `publish/latest.json` |

### 视觉 / 易用检定（按本轮模型眼光复核）

逐页复核了 `t68-00`~`t68-15` 与 6.7 的 `s18`~`s37`，并对照 `CAR-UI-SPEC-2880x1620.md` 的触控/字号下限。结论：

1. 关于页原先信息层级弱、更新入口藏在页底；本轮把「版本更新」卡片提到标题下方，设置页加「检查更新」直达行，路径从 3 步降到 2 步。
2. 状态提示条仍是 `COMPLEX_UNIT_SP 15`，车机 60–80 cm 观看距离下明显偏小；改为按屏宽 `px(34/2880)`、圆角深底、最小高度 `px(0.038)`，d160/d240 物理尺寸一致。
3. 整页副标题与分组标题对比度偏低；把 `pageRow` 副标题从 `#7C8794` 提到 `#98A5B3`、`pageLabel` 从 `#6E7A88` 提到 `#8A98A8`、`pageNote` 从 `#8C98A6` 提到 `#AAB7C5`。
4. 检查失败时「复制下载链接」变灰但原卡片没有解释；新增「原因：…」与「可重试检查 / 打开发布页」两行自救文案，按钮常驻不消失。
5. 功能页 10/10 网格、标签缩略图卡片、阅读模式 A-/A+/日间/退出按钮维持 6.7 的对齐与热区；本轮没有发现新的 P0 排版缺陷。

### 边界

- `https://dl.z736.vip/car-browser-tvb/latest.json` 当前仍是 HTTP 404；本轮没有服务器 SSH 凭据，不能代为把清单发布到公网。客户端已按其他软件约定固定该地址，清单上线后无需改 APK。
- 本地 HTTPS 桩只用于验证客户端的「连接失败/证书失败」状态与按钮调用链；不把自签 CA 或 debug-overrides 带进正式 APK。
- 未连接真车；MuMu 结果不替代零跑 B01 真车验收。

## 2026-10-23 · v6.7（versionCode 17）

- 构建：`dist/car-browser-6.7.apk`（678.6 KB）
- SHA256：`10E6D565A7895EF0DD84B5087CB355C7DC6EDF101D74573DD2A4B4FF0700F401`
- 设备：MuMu 车机档，2880×1620 @160dpi（`wm size reset; wm size 2880x1620`）
- 安装方式：`adb install -r`，冷启动后 pid 2146 全程未变

| 项目 | 操作 | 结果 | 截图 |
|---|---|---|---|
| 首屏 | 冷启动 | 13 个站点图标，布局正常 | `s18-home.png` |
| 功能页第 1 页 | 点菜单 | 10 项两行 | `s19-sheet1.png` |
| 功能页第 2 页 | 左滑 | 10 项两行（无广告过滤按钮） | `s20-sheet2.png` |
| 地址栏导航 | 输入 example.com | 正常加载 | `s21-example.png` |
| 页内查找 | 页内查找 → `domain` | 计数 1/2，两处高亮 | `s22-find.png` |
| 阅读模式 | 阅读模式 | 正文铺满，A-/A+/日间/退出 都在 | `s23-reader.png` |
| 阅读日间主题 | 点「日间」 | 整层浅色，按钮变「夜间」 | `s24-reader-light.png` |
| 标签页 | 新建标签 → 标签页 | 3 张卡片，2 张有真实缩略图，1 张走首字占位 | `s25-tabs2.png` |
| 关闭其他 | 关闭其他 → 关闭 | 3 → 1 | `s26-closeothers.png` |
| 书签 | 菜单 → 书签 | 书签 · 13，单条 ✕ 正常 | `s27-bookmarks.png` |
| 历史 | 菜单 → 历史 | 按今天/日期分组，可单删/清空 | `s28-history.png` |
| 下载 | 菜单 → 下载 | 4 条记录 + 保存路径 | `s29-downloads.png` |
| 设置 | 菜单 → 设置 | 各项状态正确，广告过滤计数可见 | `s30-settings.png` |
| 关于 | 菜单 → 关于 | 版本 6.7 | `s31-about.png` |
| 夜间模式 | 夜间开 / 关 | 页面反色 / 复原 | `s32-night.png` / `s33-night2.png` |
| 全屏 | 菜单 → 全屏 | 工具条隐藏，右上角留恢复入口 | `s34-fullscreen.png` |
| 退出全屏 | 点右上角恢复 | 工具条与页面复原 | `s35-restored.png` |
| 长按链接 | 百度热榜文字链 1s | 菜单弹出，地址已解码中文 | `s37-linkmenu.png` |

### 结论

- 全程无 `FATAL EXCEPTION`，进程 pid 稳定，Activity 始终在前台。
- 已知取舍：示例页 `example.com`（纯白页）在标签卡片里走了首字占位，说明该标签的抓图被判定为无效；缩略图逻辑本身有占位兜底，不影响使用。
