# 实机验收记录

## 2026-11-06 · v6.8（versionCode 18）

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
