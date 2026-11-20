# 来源声明 / NOTICE

## 本工程的上游

```
仓库: https://github.com/viyiviyi/tv-browser
分支: main（取用时的 HEAD）
文件: app/src/main/{java,assets,res}
```

上游作者：`viyiviyi`。上游原始说明快照见 [upstream-README.md](upstream-README.md)，不作为本工程构建或运行入口。

## 既有许可记录与分发限制

取用时的项目记录为“未发现上游 LICENSE 文件”，并保留以下限制；本次知识整理未重新核实额外上游授权：

- 本目录下的修改版**仅用于本项目所有者自用/学习**；
- **不得对外分发**（无论是免费还是收费、是否修改）；
- 若要分发，必须先向原作者取得书面许可。

### 仓库可见性（2026-11-13）

为满足自动更新对匿名 Release 读取的要求，本项目所有者决定把仓库转为公开；APK 内不嵌任何 token。
此处只保留当时决定，不将公开状态写成已取得新增授权；既有分发限制保留。若原作者提出异议，
应立即把仓库转回私有并撤下 Release 资产。

## 本项目相对上游的现状

| 部分 | 当前文件 | 说明 |
|---|---|---|
| 应用身份 | `AndroidManifest.xml`、`res/values/strings.xml` | 包名 `com.carweb.tvb`，显示名“车览浏览器”。 |
| 界面与导航 | `src/com/carweb/tvb/MainActivity.java` | 触摸工具栏、多标签、整页功能、下载与系统安装器交接；当前行为见 README。 |
| WebView 与首页 | `src/com/carweb/tvb/BrowserWebView.java`、`assets/` | 保留上游基础并有本项目修改，不能视为原样资产。 |
| 数据与桥接 | `src/com/carweb/tvb/SiteStore.java`、`src/com/carweb/tvb/BrowserBridge.java`、按键类 | 来自上游结构；具体修改以源码和 Git 为准，不作“全部未改动”的保证。 |
| 图标 | `res/mipmap-*`、`res/drawable/ic_launcher_bg.xml`、Lucide 资源 | 应用启动/品牌图与 Lucide 操作矢量分别管理；不是上游原图标。 |
| 构建 | `build.ps1`、手工 `BuildConfig.java` | Android SDK 直编，不使用上游 Gradle 工程。 |

## 上游资料与署名

保留已有上游出处；[upstream-README.md](upstream-README.md) 是原始说明快照，其相对链接、目录、命令与 TV 行为不代表当前车机版。现役指南只有 [README.md](README.md)，历史变更由 Git 和 [VERIFY.md](VERIFY.md) 追溯。

## Lucide 界面图标（v6.22 起）

操作图标来自 `https://github.com/lucide-icons/lucide`；固定提交与逐文件摘要统一见 [来源清单](third_party/lucide/source.json)。原始 SVG 和完整 ISC/Feather MIT 许可保存在 `third_party/lucide/`；APK 内附 `assets/licenses/lucide.txt`。

[导入脚本](tools/import_lucide.py) 将 SVG 几何转换为原生矢量 XML，规范化圆弧标志分隔，保留 24×24 视框、2px 描边及圆角连接。普通构建和运行使用内置资源，不联网取图标。
