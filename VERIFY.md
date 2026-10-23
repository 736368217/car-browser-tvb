# 实机验收记录

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
