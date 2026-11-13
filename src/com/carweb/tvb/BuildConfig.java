package com.carweb.tvb;

/**
 * 手工替代 AGP 生成的 BuildConfig。
 *
 * 上游工程用 Gradle/AGP 构建，代码里引用了 BuildConfig.DEBUG；本项目改走
 * aapt2 + javac + d8 的直编链路，没有 AGP，所以这一份得自己给。
 *
 * DEBUG 取 true 是刻意的：它只控制两处诊断能力 ——
 *   1) WebView.setWebContentsDebuggingEnabled(true)：可以接 chrome://inspect 看页面；
 *   2) onConsoleMessage 把网页 console 输出打到 logcat。
 * 车机是自用设备，验收时"页面为什么白屏/按钮没出来"全靠这两条，比省这点开销重要。
 */
public final class BuildConfig {
    public static final boolean DEBUG = true;
    public static final String APPLICATION_ID = "com.carweb.tvb";
    public static final String BUILD_TYPE = "release";
    public static final int VERSION_CODE = 24;
    public static final String VERSION_NAME = "6.14";

    private BuildConfig() {
    }
}
