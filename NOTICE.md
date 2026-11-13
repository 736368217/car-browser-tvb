# 来源声明 / NOTICE

## 本工程的上游

```
仓库: https://github.com/viyiviyi/tv-browser
分支: main（取用时的 HEAD）
文件: app/src/main/{java,assets,res}
```

上游作者：`viyiviyi`。完整的上游 README 见同目录 `upstream-README.md`。

## 许可状况（重要）

**上游仓库没有 LICENSE 文件。** 按伯尔尼公约与各国版权法默认规则，
未声明许可即「保留所有权利」。因此：

- 本目录下的修改版**仅用于本项目所有者自用/学习**；
- **不得对外分发**（无论是免费还是收费、是否修改）；
- 若要分发，必须先向原作者取得书面许可。

### 仓库可见性（2026-11-13）

为满足自动更新对匿名 Release 读取的要求，本项目所有者决定把仓库转为公开；APK 内不嵌任何 token。
这与上面的许可结论相冲突（上游未授权再分发）。公开属于所有者的决定；若原作者提出异议，
应立即把仓库转回私有并撤下 Release 资产。

## 本项目相对上游的改动

| 改动 | 文件 | 原因 |
|---|---|---|
| 包名 `com.dsh.tvbrowser` → `com.carweb.tvb` | 全部 `.java`、`AndroidManifest.xml` | 与上游包名区分，避免与上游/release 版冲突 |
| 应用名 `电视浏览器` → `车机浏览器 B` | `res/values/strings.xml` | 车内桌面上与 D 线区分 |
| 图标替换为自绘矢量 | 删 `res/mipmap-*`、`res/drawable/app_banner.png`；新增 `res/drawable/ic_launcher.xml` | 不复用原作者图标 |
| 加顶部工具条（地址栏 + 5 键） | `MainActivity.java` 新增 `buildChrome()` 等约 250 行 | 上游无地址栏，车机触摸屏需要随时改地址 |
| `uses-feature` 全表置 `required=false` | `AndroidManifest.xml` | 车机安装器按 required 特性过滤 |
| 手写 `BuildConfig` | `BuildConfig.java`（新增） | 本项目不用 Gradle/AGP，需要自带 |
| 构建方式改为 aapt2+javac+d8 | `../build.ps1` | 本机无 gradle 命令 |

未改动的上游资产：`BrowserWebView`（长按悬停、遥控器按键）、`KeyPolicy`、`RemoteKey`、
`SiteStore`、`BrowserBridge`、`assets/`（首屏、注入桥、空间导航脚本）。

## 保留的上游署名

`BrowserWebView.java`、`MainActivity.java` 等文件顶部的原始注释与类说明未删除，
`remote key` 策略、`TOUCH_HOVER_MS = 400` 的取值理由等说明性注释均原样保留。
