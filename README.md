# 车览（CHELAN）

给 **17.3 英寸触摸车机**（零跑 B01，Android 12L / API 32 / 2880×1620）用的 WebView 浏览器。

基于 [`viyiviyi/tv-browser`](https://github.com/viyiviyi/tv-browser) 改造 —— 上游是给电视遥控器设计的，
这个仓库把它改成**手指触摸优先**，并补上了车机上真正会用到的浏览功能。

> 上游仓库没有 LICENSE 文件（默认保留所有权利），所以**本仓库仅供自用/学习，不对外分发**。
> 详见 [NOTICE.md](NOTICE.md)。

---

## 截图与实测

| 项 | 值 |
|---|---|
| 目标分辨率 | 2880×1620 @160dpi，横屏 |
| 工具条 | 高 132px，7 个按钮各 120×132，地址栏 2088×96 垂直居中 |
| 首屏 | 7 列网格，瓦片 288×288，列距 63，左右边距各 242 |
| 渲染内核 | Android System WebView（随车机固件，无法升级） |

---

## 它解决的两个真问题

### 1. 长按 0.4 秒 = 鼠标悬停

触屏上根本没有 `hover` 这回事：点一下触发 `click` 就走开了。而大量**电脑版网页**把关键操作
藏在 CSS `:hover` 里 —— 播放器的"立即播放"浮层、下拉菜单、卡片小按钮、tooltip，手指够不着。

本项目的做法：手指按住不动 **400ms**，就向该坐标派发一次真实的 `ACTION_HOVER_MOVE`，
并把事件源标成 `SOURCE_MOUSE`。页面于是以为有鼠标停在那里。松手后悬停**保留**，
因为接下来通常就是要点 hover 出来的那个按钮；手指移动超过 touch slop 则取消，不干扰滚动。

400ms 是刻意取的：早于系统长按判定（500ms），抢在文本选择手柄弹出之前派发。

### 2. 首屏是触摸版，不是遥控器版

上游首屏按 `min(W×0.155, H×0.19)` 上限 168px 算卡片，在 2880 宽的车机上排成 12 列小图标。
本仓库改成固定 7 列 × 10vw（288px），搜索框从中间挪到最上，长按卡片弹出收藏/删除/排序菜单
（上游只有遥控器的 `kb-longpress`，触摸屏叫不出来）。

---

## 功能

| 分组 | 功能 |
|---|---|
| 导航 | 返回 / 前进 / 刷新 / 主页 / 地址栏（网址或搜索词） |
| 标签 | 多标签栈（最多 5 个，首屏永驻栈底）+ 标签列表切换 |
| 收藏 | 长按卡片收藏/取消/删除；↑前移 ↓后移 排序 |
| 页面 | 桌面版↔手机版 UA 一键切换、双指缩放、清除缓存 |
| 媒体 | 视频全屏（`onShowCustomView`）、免手势自动播放 |
| 下载 | 接系统 DownloadManager，带 UA 与 Cookie |
| 外观 | 沉浸式全屏、矢量图标（24 视框标准绘制） |
| 输入 | 地址栏回车即走、软键盘自动收起 |

## 构建

要求：JDK 17+、Android SDK（build-tools 34、platform android-35）。不需要 Gradle。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
# 产物：dist\car-browser-<版本>.apk
```

首次构建会自动生成签名证书到 `keystore/carweb.jks`（**不进仓库**）。
想用自己的证书：设 `CARWEB_KEYSTORE` / `CARWEB_KEYSTORE_PASS` / `CARWEB_KEYSTORE_ALIAS`。

### 两个构建坑（已修，别踩回去）

1. **`build.ps1` 必须存成 UTF-8 with BOM**。PowerShell 5.1 会按 ANSI(GBK) 解析无 BOM 的 UTF-8，
   中文注释把引号配对打乱，报出莫名其妙的 `Unexpected token 'res.zip'`。
2. **`$ErrorActionPreference` 不能是 `Stop`**。keytool / javac 把正常信息写到 stderr，
   Stop 模式下会被当成 terminating error 中断构建 —— keytool 明明已经生成了证书，脚本却停住。

## 目录

```
AndroidManifest.xml      清单（uses-feature 全 false，车机安装器按特性过滤）
build.ps1                构建脚本（aapt2 + javac + d8 + apksigner）
src/com/carweb/tvb/
  MainActivity.java      版面、工具条、标签、菜单、下载
  BrowserWebView.java    WebView 配置、长按悬停、遥控器按键
  GlyphIcon.java         矢量图标（24 视框）
  BookmarkStore.java     SiteStore：收藏/最近/搜索引擎
  BrowserBridge.java     网页 → 原生的窄接口（kbHost）
  KeyPolicy.java         遥控器按键策略
  RemoteKey.java         按键映射
assets/
  home.html              首屏结构
  home.css               首屏样式（vw 量纲）
  home.js                首屏逻辑（渲染、长按、排序）
  keynav-web.js          上游空间导航脚本（上游构建产物，原样保留；源码在上游 src-web/）
res/drawable/ic_launcher.xml   启动图标（矢量）
```

## 版本

- **3.0** — 更名「车览」、启动图标重绘（浏览器窗口 + 地平线 + 落日）、首页品牌区与卡片底色重做
  （低饱和渐变 + 首字母/图标严格互斥）、默认收藏两行铺满不再折叠
- **2.0** — 首屏重做（7 列网格 + 搜索置顶 + 修 logo/文字重叠）、触摸长按、7 个工具条按钮、
  标签切换、UA 切换、下载、双指缩放、图标全套重绘
- **1.0** — 车机化首版：加工具条与地址栏、换包名与图标、清单适配
