# 车览浏览器项目约束

## 定位与入口

- 本仓是包名 `com.carweb.tvb` 的触摸车机 WebView 浏览器，不是 TVBox 播放器。
- 用法、构建、验收和发布以 [README.md](README.md) 为入口；版本/SHA 与开放验证项以 [VERIFY.md](VERIFY.md) 为准。
- [NOTICE.md](NOTICE.md) 保留来源与许可限制；`upstream-README.md` 是上游快照，不是本仓操作指南。
- 本文件是项目规则真身；不要再建内容独立、会互相漂移的 CLAUDE.md。

## 技术栈与构建

- Java/Android 框架，aapt2 → javac → d8 → zipalign → apksigner；不使用 Gradle。
- JDK 17+，固定 Build Tools 35.0.0 / compile SDK 35；minSdk 21、targetSdk 33。根目录运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1`。
- `src/com/carweb/tvb/` 放原生代码，`assets/` 放首页与注入脚本，`res/` 放 Android 资源，`tests/native/` 放独立审计。
- 版本必须同时更新 AndroidManifest.xml 与手工 BuildConfig.java。保留原签名；生成新证书不等于可覆盖升级。
- 保留含中文 PowerShell 脚本的 UTF-8 BOM，逐步检查真实退出码。审计构建显式设置 ANDROID_HOME，差异见 README。

## 界面与图标

- 后退/前进在地址栏工具栏内，不恢复页面级浮动返回按钮；全屏必须保留可点的“退出全屏”入口。
- 操作图标使用仓库内 Lucide 矢量；SVG、生成资源、来源摘要、许可同步维护，不改站点品牌 Logo 冒充统一图标。
- 图标转换注意 SVG 紧凑圆弧标志与 Android 解析差异；保留全套一致的安全边距，测试裁切、颜色及透明度。

## 验收与发布边界

- 用户发布约定：每次更新通过相关验证后，默认提交并推送 `main`，发布到 R2 主通道并同步 GitHub Release 兜底。用户指定“仅本地”或“暂缓发布”时，按指定范围交付。
- 应用内容变化时递增版本并验收新包。文档和注释更新也提交推送，并复核或补齐当前版本的发布渠道，沿用已验收 APK 的原字节。
- MuMu 仅在自己的全局池租约下操作，每次设备动作前 check；静音、实例级车机显示、偏好备份/恢复与 release 缺一不可。不要复制项目级实例池或硬编码他人的 owner/实例。
- 池工具为用户目录下 `.mumu-pool/mumu-pool.py`；设备动作使用本环境的 MuMu 插件。无法满足租约门禁就停止设备操作。
- 按 guest 实际安装 base.apk SHA 关联完整业务 JSON、节点/截图及崩溃结果；进程活着或 instrumentation 返回 0 不是业务通过。
- `tour-*.json` 可能固定旧版本，fixture 的默认更新 APK 也可能是旧包；选计划和设置 CARWEB_AUDIT_APK 后再验。
- MuMu API 35 不代表 B01 API 32 真车通过。不同 SHA/版本的通过数分别记录，不能拼成当前版全量通过。
- 发布入口是 tools/publish_r2.ps1，R2 发布后同步 GitHub Release 兜底；逐项核对两端版本、大小、SHA 与被测包一致，不能只看 HTTP 200 或 Release URL。
- 旧 publish-manifest.ps1、publish/ 和停用域名仅作历史，不执行或恢复。沿用已验收包时保留其原字节和 SHA。

## 产物与交接

- build/、dist/、keystore/、Python 缓存不进 Git；smoke/ 仅显式归档选定证据，禁止加入私钥、偏好备份或凭据。
- 已验收 APK 不得被同版本重建包悄悄替换；本地旧文件名不是回滚依据，先核对 SHA。
- 只编辑当前项目。文档收尾不自动删除遗留物；候选路径和原因写入 VERIFY，按用户的具体授权处理。
- 当前发布、原始证据和下一步验证见 VERIFY；保留按版本冻结的报告。平台生成记忆和全局规则不手改。
