# 验收与发布证据

当前用法、构建与发布步骤见 [README.md](README.md)。本文件只记录版本身份、已验证范围和仍未验证的边界；旧版本的通过数不能累加为当前版全量通过。

## 当前发布身份（2026-11-21 UTC 核对）

| 项 | 结果 |
|---|---|
| 应用 | `com.carweb.tvb`，Manifest / BuildConfig 为 **6.23 / 33** |
| R2 主通道 | `car-browser/car-browser-6.23.apk`，**763,750 bytes** |
| 已发布 / 已安装 APK SHA-256 | `db287ece7d62307d2aef58c4640ca8d8b8e2aeb67adc201b07d97a134b83055f` |
| R2 回读 | `latest.json` 与 `history/6.23.json` 均为 6.23/33；完整下载为 763,750 B，SHA 与最终安装包一致。R2 `publishedAt` 为 `2026-11-20T17:36:47Z`。 |
| GitHub 兜底 | `v6.23` 于 `2026-11-20T17:32:02Z` 发布；资产摘要与本地包一致，完整下载亦为 763,750 B / 同 SHA。 |
| 证据归档 | [6.23 清单](smoke/v623/motion-db287ece/manifest.json)，160 个逐文件 SHA/大小记录；清单另计。 |

源码、原生验收及证据提交为 `d906ba99a086576bd9649373c939c9387bfe9b9d`，已推送至 `main`；GitHub `v6.23` 标签指向本轮构建提交。

## 6.23：菜单与页面动效

[离线效果与验收报告](reports/motion-6.23.html) 内嵌 7 张 2880×1620 原生画面，附逐文件证据清单。

| 检查 | 结果 | 可复核来源 |
|---|---:|---|
| 页面交互 | 23/23，失败 0 | [result.json](smoke/v623/motion-db287ece/final-pages-r2/result.json)，guest 安装 SHA 与上表一致 |
| 动画倍率 1.0 | 23/23，失败 0 | [motion.json](smoke/v623/motion-db287ece/final-motion/motion.json)，逐帧记录 228 帧、5 张动画中间态截图 |
| 系统动画关闭 | 23/23，失败 0 | [motion.json](smoke/v623/motion-db287ece/final-motion-disabled/motion.json)；另有 [即时菜单截图检查](smoke/v623/motion-db287ece/final-motion-disabled-snapshot/result.json) 1/1 |
| 崩溃 | 页面、动画及倍率关闭批次的 crash buffer 均为 0 B | 各批次 `crash.txt` |
| 构建与签名 | Java、d8、zipalign、v1/v2/v3 签名通过 | Build Tools 35.0.0 / compile SDK 35，沿用原证书 SHA-256 `8eec23c3c7c129ab422c3f61ee2981abac7b397cf947b1c8fbef22ac6ffaeb55` |
| 设备恢复 | 四份 SharedPreferences 逐文件 cmp 通过；动画倍率恢复 0；审计 APK、guest 输出和本轮备份移除 | 恢复脚本输出 `RESTORE_OK`、`TEST_APK_ABSENT`、`MOTION_OUTPUTS_ABSENT`、`BACKUP_REMOVED` |

设备为 MuMu 6.8.7 / Android 15 API 35；截图前确认 `init=1620x2880 160dpi … noscale`、`cur=2880x1620 app=2880x1620`、`w2880dp h1620dp 160dpi … land`，音量 0。6.22 对照 SHA 为 `89f5bfa20f748dfc8a502ed148f100e356de0fde2072b7a99f2148e583c2faa7`，原页面计划 23/23，动效审计 22/22。菜单最大进入位移由 691 px 降至 52 px；父页返回最低 alpha 为 0.88，滚动位置 `832/832 px` 恢复；全屏仍保留可点的恢复手柄。

**未验证：**零跑 B01 Android 12L/API 32 真车、应用内在线检查/安装更新流程、额外人工视觉评分。报告中的截图来自原生验收；Ego 报告页截图通道超时，未据此声称完成浏览器视觉评审。MuMu Android 15 结果不替代真车验收。

## 6.22：图标与页面专项

[离线效果报告](reports/lucide-6.22.html) 嵌入 5 张 2880×1620 原生页面截图及 1 张 1600×650 原生图标总览。

| 检查 | 结果 | 可复核来源 |
|---|---:|---|
| 页面操作 | 23/23，失败 0 | [result.json](smoke/v622/lucide-89f5bfa2/result.json)；实际安装 SHA 与上表一致 |
| 图标渲染 | 40/40，失败 0 | [icons.json](smoke/v622/lucide-89f5bfa2/icons.json)；24/64/78px，非空、边界、着色、透明度及非正方形 bounds 等比检查 |
| 崩溃 | crash buffer 0 B | [crash.txt](smoke/v622/lucide-89f5bfa2/crash.txt) |
| 构建与签名 | Java、d8、zipalign、v1/v2/v3 签名通过 | Build Tools 35.0.0 / compile SDK 35；沿用原证书，证书 SHA-256 `8eec23c3c7c129ab422c3f61ee2981abac7b397cf947b1c8fbef22ac6ffaeb55` |
| 独立启动 | Status ok、前台 MainActivity、PID 存活 | 当轮设备检查；不据此替代业务断言 |

设备为 MuMu 6.8.7 / Android 15 API 35：`init=1620x2880 160dpi … noscale`、`cur=2880x1620 app=2880x1620`、`w2880dp h1620dp 160dpi … land`，音量 0。四份 SharedPreferences 经逐文件 SHA 恢复；辅助审计 APK、guest 计划和本轮输出已清理，自己的 vm0 租约已释放、MuMu 主程序保留。

最初候选的 SVG 紧凑圆弧参数不兼容 Android PathParser，已由转换器规范化；小尺寸抗锯齿贴边通过全套统一安全边距解决。旧候选失败未计入通过。颜色审计只对不透明像素精确比较颜色，透明度单独检查，避免预乘舍入造成误判。

**未验证：**零跑 B01 Android 12L/API 32 真车；6.22 的 app 在线检查更新全流程；额外人工视觉评分。浏览器预览截图通道超时，报告保留的是实际 Android 截图和原生像素检查。6.21 的后端 53 项、6.20 的全量批次均不是 6.22 新跑的结果。

## 近版历史索引

历史报告保留当时事实与限制，不按当前线上状态改写，也不作为现役操作手册。

| 版本 | 当时验收 / 发布 SHA-256 | 覆盖与证据 |
|---|---|---|
| 6.21（31） | `affb9ec8e0bbb4f78d6e5dd5148b97873ab8fdd12406d9e813b8e713a7e07d6a` | 731,764 B；旧下载任务打开 4/4，下载/更新后端 53/53。MuMu 在线检查 5/6，末步 `connection closed` 未通过。见 [下载恢复报告](reports/download-recovery-6.21.html)。原始 JSON 位于当时的本机共享证据目录，未归档到本仓，不能声称可在新 clone 中完整复验。 |
| 6.20（30） | `6f849f91e94b07f824a716f43b6cb4b1b68b54e59b08be65d809f08285887d28` | 731,764 B；核心 34/34、后端 43/43、UX 18/18、网页 19/19、SAF 11/11、SAF 重启 3/3、更新流程 14/14、当时发布后在线 6/6。见 [6.20 报告](reports/main-full-20261120.html)，各专项有交叉覆盖。 |
| 6.19（29） | `28123895330278ea6e8236d278072e54a0ba3ab1b6994420db0074c71793967b` | 727,668 B；当时记录为全量 34/34、UX 18/18、后端 18/18。画廊和 107 项清单仅存在于本机 `smoke/v619/final-28123895/`，未纳入 Git，不作为新 clone 中可用的证据链接。 |

6.21 报告将一次 EACCES 泛化归因于“独立审计 UID”的说法仅为当时推断；实际查到的夹具父目录是 root/700。后续按真实目录、父权限、SELinux 和进程 UID 取证，不仅凭审计 APK 名称推断权限。

6.18 及更早的发布、性能对照、导航/SAF 演进和原始限制，保留在 Git 的旧版验收记录中，可只读查看：

```powershell
git show cb36caabd53549138a814828f36d73fc2607091a:VERIFY.md
```

该快照是历史，不应从中恢复已退役的浮动返回、旧更新源或模拟器操作方式。[upstream-README.md](upstream-README.md) 同样仅供上游溯源，里面的路径和命令不是本工程入口。

## 不能丢掉的复测要点

- **包身份：**先在 guest 重算安装 `base.apk` SHA，再关联业务结果。固定工具链不等于签名 ZIP 字节可复现；同名 APK 或同版本重建包不自动继承原验收。
- **版本来源：**Manifest 与手工 BuildConfig 必须一致；曾出现安装是 6.21、关于页却显示 6.20 的候选，不能只验 PackageManager。
- **打开文件：**选择器弹出不等于内容可读。要验证真实字节/预览；私有目录 fixture 使用 Context 的真实目录，并核对父目录 UID、权限与 SELinux，不能把 root 创建目录的 EACCES 当作产品路径缺失。
- **SAF：**目录授权必须由目标应用持有并验证跨进程读回；选择器打开或外部进程可读都不能单独证明授权生效。真实缺失文件可重试，失效 SAF 授权需用户重新授予。
- **下载生命周期：**取消后立即重试不能遗留旧 worker；截断响应不得完成；删除文件与仅删记录分开。DownloadManager 的 pending/running/paused 状态不能直接判失败。
- **更新与安装器：**校验错误大小/SHA 的拒绝及残留清理；API 33+ 下载完成广播需要匹配实际系统发送方。产品“已交给安装器”与可解析 APK handler 是验收条件，不强制 MuMu 前台覆盖包名等于 PackageManager handler。
- **工具与结果：**instrumentation 返回 0 可伴随业务失败；优先看完整 JSON 的顶层异常和逐项检查。没有完整结果的模拟器停止批次不计通过；UiAutomator 自身异常与目标应用 crash 分开记录。
- **性能与视觉：**旧版本的帧时间对照、其他 SHA 的截图不能证明当前版本流畅或已人工评审。

## 本地遗留物与待处理边界

2026-11-20 只读盘点确认以下对象仍在，本次未删除、移动或替换：

| 对象 | 状态 / 为什么不能直接使用 |
|---|---|
| `dist/car-browser-6.20.apk` | 本地 SHA 为 `532edd77367d8765b44e6622fd68719ee8f0e73519a89e803e10d5f7af013ee7`，是下载恢复候选；与上面的 6.20 验收/发布 SHA 不同，不能当作原发布回滚包。 |
| `dist/car-browser-6.18.apk` | 本地 727,668 B，SHA `7e4cba4a02a8554ba60cd7e3f221260a30c28e053970a34339eff371203467e8`，不符历史记录中的 723,572 B / `c1670551c9d091c4f3b5d01dce04dd193b6519427511f51df2c431c18e21e05b`。 |
| `dist` 中 6.15–6.19 的 APK 及各自 `.idsig` | 超出当前 6.22 + 两个历史版本的留存目标，仅列为删除候选；尚无本次针对这些具体路径的删除同意。需要回滚时从对应 R2 history 读取身份并校验原包，不能只看文件名。 |
| `publish-manifest.ps1`、`publish/` | 6.8 旧格式遗留；本地 `publish/latest.json` 为 6.8（18），不是 R2 现役清单。旧域名永久停用，不探测或恢复。 |
| `smoke/v619/final-28123895/` | 本机历史证据，共 107 个文件的大小/SHA 本次复核匹配，但该目录被忽略且未跟踪；新 clone 不会自动得到它。可能含独有原始证据，不列入自动删除或自动提交。 |

本仓未发现项目级可维护记忆文件；平台生成记忆、全局规则和相邻项目未修改。开放项仍是明确的真车/在线/视觉验证边界及待确认的旧文件清理，没有用本次文档整理代替这些工作。
