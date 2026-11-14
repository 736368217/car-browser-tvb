package com.carweb.tvb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;

/**
 * 电视机上的浏览器。
 *
 * 启动进本地首屏（assets/home.html：搜索框 + 收藏 + 最近打开），
 * 从首屏点开的网站一律开成**新标签页**：首屏留在最底下，返回键就是关掉
 * 当前标签页、原地回到上一个 —— 不会像"当前页跳转"那样重新加载一遍。
 *
 * 标签页栈的第一个永远是首屏，回收标签页时不会把它回收掉，
 * 所以不管逛到哪儿、按多少次返回，最后总能回到首屏；在首屏再按返回就是退出确认。
 */
public class MainActivity extends Activity implements BrowserWebView.Listener {

    public static final String TAG = "TvBrowser";

    /** 首屏：assets 里的本地页面 */
    public static final String HOME_URL = "file:///android_asset/home.html";

    /** 同时最多留几个标签页（电视盒子内存有限，太多了会被系统杀掉） */
    private static final int MAX_TABS = 5;

    /** "正在加载"提示最多显示多久（页面迟迟不 onPageFinished 时别一直挡着） */
    private static final long LOADING_TIMEOUT_MS = 6000L;

    private FrameLayout root;
    /** 标签页容器：一个标签页一个 WebView，只显示最上面那个 */
    private FrameLayout host;
    private TextView status;
    /** 顶部加载进度条 */
    private View loadBar;

    private final ArrayDeque<BrowserWebView> tabs = new ArrayDeque<>();
    /** 当前显示的是哪一个。不再靠"栈顶"表示，首屏才能被切到前面而不打乱标签顺序 */
    private BrowserWebView activeWeb;
    /** 首屏那一个，永远留在栈底 */
    private BrowserWebView homeView;

    /**
     * 标签缩略图：切走/关掉之前抓一张当前画面。
     *
     * 用 PixelCopy 直接抄屏幕上的硬件图层，比 WebView.draw(Bitmap) 靠谱 ——
     * 后者在硬件加速下经常只画出一张空白。只在标签可见时抓，所以不会掺进遮罩。
     */
    private final java.util.IdentityHashMap<BrowserWebView, Bitmap> tabThumbs =
            new java.util.IdentityHashMap<>();
    /** 上一次抓图的时间：同一标签短时间内连着触发（onPageCommitVisible + onPageFinished）只抓一次 */
    private final java.util.IdentityHashMap<BrowserWebView, Long> thumbStamps =
            new java.util.IdentityHashMap<>();

    private SiteStore store;

    private View fullscreenView;
    private BrowserWebView fullscreenOwner;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private long lastExitAt = 0L;

    /* ---- 持久化界面状态与应用内返回 ---- */
    private static final String UI_PREFS = "ui_state";
    private static final String KEY_NIGHT = "night_mode";
    private static final String KEY_NO_IMAGE = "no_image_mode";
    private static final String KEY_AD_BLOCK = "ad_block";
    private static final String KEY_FLOAT_BACK = "float_back";
    private static final String KEY_FLOAT_BACK_SECONDS = "float_back_seconds";
    private static final String KEY_PENDING_DL = "pending_download_id";
    private static final String KEY_PENDING_SHA = "pending_download_sha";
    private static final String KEY_PENDING_SIZE = "pending_download_size";
    private static final String KEY_PENDING_VERSION = "pending_download_version";
    private SharedPreferences uiPrefs;
    private ImageView floatBack;
    private boolean floatBackEnabled = true;
    private int floatBackSeconds = 4;
    private final java.util.ArrayDeque<PageFrame> pageParents = new java.util.ArrayDeque<>();
    private String currentPageRoute = "";
    private int currentPageScrollY;
    private long pageTransitionId;

    private static final class PageFrame {
        final View view;
        final String route;
        final int scrollY;
        PageFrame(View view, String route, int scrollY) {
            this.view = view;
            this.route = route;
            this.scrollY = scrollY;
        }
    }

    private final Runnable hideFloatBackTask = new Runnable() {
        @Override public void run() {
            if (floatBack == null) return;
            floatBack.animate().cancel();
            floatBack.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
                @Override public void run() {
                    if (floatBack != null) floatBack.setVisibility(View.GONE);
                }
            }).start();
        }
    };

    private void loadUiState() {
        nightMode = uiPrefs.getBoolean(KEY_NIGHT, false);
        noImageMode = uiPrefs.getBoolean(KEY_NO_IMAGE, false);
        AdBlock.setEnabled(uiPrefs.getBoolean(KEY_AD_BLOCK, true));
        floatBackEnabled = uiPrefs.getBoolean(KEY_FLOAT_BACK, true);
        int seconds = uiPrefs.getInt(KEY_FLOAT_BACK_SECONDS, 4);
        floatBackSeconds = (seconds == 6 || seconds == 8) ? seconds : 4;
        // 更新下载可能跨 Activity 重建：把待安装的下载 id 与期望校验值一起恢复
        pendingDownloadId = uiPrefs.getLong(KEY_PENDING_DL, -1L);
        pendingSha = uiPrefs.getString(KEY_PENDING_SHA, "");
        pendingSize = uiPrefs.getLong(KEY_PENDING_SIZE, 0L);
        pendingVersion = uiPrefs.getString(KEY_PENDING_VERSION, "");
    }

    private void saveUiState() {
        if (uiPrefs == null) return;
        uiPrefs.edit().putBoolean(KEY_NIGHT, nightMode)
                .putBoolean(KEY_NO_IMAGE, noImageMode)
                .putBoolean(KEY_AD_BLOCK, AdBlock.isEnabled())
                .putBoolean(KEY_FLOAT_BACK, floatBackEnabled)
                .putInt(KEY_FLOAT_BACK_SECONDS, floatBackSeconds)
                .putLong(KEY_PENDING_DL, pendingDownloadId)
                .putString(KEY_PENDING_SHA, pendingSha == null ? "" : pendingSha)
                .putLong(KEY_PENDING_SIZE, pendingSize)
                .putString(KEY_PENDING_VERSION, pendingVersion == null ? "" : pendingVersion)
                .apply();
    }

    private void buildFloatBack() {
        floatBack = new ImageView(this);
        floatBack.setImageDrawable(new GlyphIcon(GlyphIcon.BACK, 0xFFF1F4F6));
        floatBack.setContentDescription("应用内返回");
        int size = px(0.046f);
        floatBack.setPadding(size / 4, size / 4, size / 4, size / 4);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE6222925);
        bg.setStroke(Math.max(2, px(1f / 2880f)), 0x665A7566);
        floatBack.setBackground(bg);
        floatBack.setVisibility(View.GONE);
        floatBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleAppBack(); }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = px(0.018f);
        lp.bottomMargin = px(0.075f);
        root.addView(floatBack, lp);
    }

    private void wakeFloatBack() {
        if (floatBack == null || !floatBackEnabled) return;
        if (sheetLayer != null && sheetLayer.getVisibility() == View.VISIBLE) {
            uiHandler.removeCallbacks(hideFloatBackTask);
            floatBack.animate().cancel();
            floatBack.setVisibility(View.GONE);
            return;
        }
        uiHandler.removeCallbacks(hideFloatBackTask);
        floatBack.animate().cancel();
        floatBack.setVisibility(View.VISIBLE);
        floatBack.setAlpha(1f);
        floatBack.bringToFront();
        uiHandler.postDelayed(hideFloatBackTask, floatBackSeconds * 1000L);
    }

    private void handleAppBack() {
        if (sheetLayer != null && sheetLayer.getVisibility() == View.VISIBLE) { hideSheet(); return; }
        if (pageVisible()) { closePage(); return; }
        if (findBar != null && findBar.getVisibility() == View.VISIBLE) { hideFindBar(); return; }
        if (toolbarHidden) { setFullscreen(false); return; }
        if (fullscreenView != null) {
            BrowserWebView owner = fullscreenOwner;
            if (owner != null) onFullscreenExit(owner);
            return;
        }
        if (addr != null && addr.hasFocus()) {
            addr.clearFocus();
            BrowserWebView focused = activeTab();
            if (focused != null) focused.requestFocus();
            return;
        }
        BrowserWebView web = activeTab();
        if (web == null || web == homeView || web.isHomePage()) {
            showStatus("已经在主页", 1500L);
            return;
        }
        if (web.canGoBack()) web.goBack();
        else closeCurrentTab();
        updateToolbarState();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) wakeFloatBack();
        return handled;
    }

    /* ---- 车机工具条（B 线新增，见 buildChrome） ---- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        store = new SiteStore(this);
        uiPrefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        loadUiState();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        host = new FrameLayout(this);
        buildChrome();
        uiHandler.postDelayed(addrSync, 500);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 顶部加载进度条：3px，随 onProgressChanged 长；加载完淡出
        loadBar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xFF3F80C0);
        loadBar.setBackground(barBg);
        loadBar.setAlpha(0f);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(0, px(3f / 2880f));
        barLp.topMargin = px(132f / 2880f);
        root.addView(loadBar, barLp);


        buildFindBar();
        FrameLayout.LayoutParams findLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(96f / 2880f));
        findLp.topMargin = px(132f / 2880f);
        root.addView(findBar, findLp);

        status = new TextView(this);
        status.setTextColor(0xFFF2F4F8);
        // 车机提示条：全部按屏宽比例，不再用 SP / density（d160 与 d240 同物理尺寸）
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(34f / 2880f));
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xEE151C24);
        status.setPadding(px(0.018f), px(0.008f), px(0.018f), px(0.008f));
        status.setMinHeight(px(0.038f));
        status.setLineSpacing(px(0.003f), 1f);
        status.setVisibility(View.GONE);
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(0xEE151C24);
        statusBg.setCornerRadius(px(0.012f));
        status.setBackground(statusBg);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusParams.bottomMargin = px(0.025f);
        root.addView(status, statusParams);

        // 全屏时工具条整条 GONE，必须留一个触屏能点的恢复入口，否则用户被困在网页里
        toolbarHandle = new ImageView(this);
        toolbarHandle.setImageDrawable(new GlyphIcon(GlyphIcon.MENU, 0xFFE8EDF2));
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.OVAL);
        handleBg.setColor(0xA6141A22);
        toolbarHandle.setBackground(handleBg);
        int handleSize = px(0.03f);
        toolbarHandle.setPadding(handleSize / 4, handleSize / 4, handleSize / 4, handleSize / 4);
        toolbarHandle.setVisibility(View.GONE);
        toolbarHandle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreen(false);
            }
        });
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                handleSize, handleSize, Gravity.TOP | Gravity.END);
        handleLp.topMargin = px(0.012f);
        handleLp.rightMargin = px(0.012f);
        root.addView(toolbarHandle, handleLp);
        buildPageLayer();
        buildSheet();
        buildFloatBack();
        registerUpdateReceiver();
        DownloadCenter.get(this).setListener(new DownloadCenter.Listener() {
            @Override public void onDownloadsChanged() { MainActivity.this.onDownloadsChanged(); }
        });
        setContentView(root);

        // 每次都从首屏开始：电视上"接着上次继续"的收益远不如"一打开就能搜"来得实在
        openHome();
    }

    /* ============================================================ 车机工具条 == */
    /*
     * B 线相对上游唯一的版面改动：顶部加一条常驻工具条。
     *
     * 上游的设计是"首屏搜索 + 遥控器返回键"，没有地址栏 —— 在电视上用遥控器没问题，
     * 但车机是 17.3 寸触摸屏，用户会想在任意页面直接改地址、直接后退/刷新。
     *
     * 尺寸统一按屏宽比例算（分母 2880 = 车机屏宽），不按 dp：
     * 目标机 density 可能是 160 也可能是 240，用物理像素比例才能在两种密度下
     * 得到相同的视觉尺寸。
     */

    private LinearLayout column;
    private LinearLayout toolbar;
    private EditText addr;
    private ImageView btnStar;
    /** 全屏（隐藏工具条）时右上角的恢复按钮 */
    private ImageView toolbarHandle;
    private GlyphIcon gBack;
    private GlyphIcon gFwd;
    private GlyphIcon gStar;
    private int screenW;
    private boolean addrEditing = false;

    /** 地址栏跟随当前页面 URL：有些页面 onPageFinished 很晚甚至不来，靠 500ms 轮询兜底 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable addrSync = new Runnable() {
        @Override
        public void run() {
            syncAddressBar();
            uiHandler.postDelayed(this, 500);
        }
    };

    private int px(float ratio) {
        return Math.max(1, Math.round(screenW * ratio));
    }

    private void buildChrome() {
        screenW = getResources().getDisplayMetrics().widthPixels;

        final int tbH = px(132f / 2880f);
        final int btnW = px(120f / 2880f);
        final int iconPx = px(64f / 2880f);
        final int pad = px(24f / 2880f);
        final int addrH = px(96f / 2880f);
        final int textPx = px(40f / 2880f);
        final int iconPad = Math.max(0, (btnW - iconPx) / 2);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(0xFF12161C);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(pad, 0, pad, 0);

        ImageView bBack = makeBtn(GlyphIcon.BACK, iconPad, "后退");
        gBack = (GlyphIcon) bBack.getDrawable();
        ImageView bFwd = makeBtn(GlyphIcon.FORWARD, iconPad, "前进");
        gFwd = (GlyphIcon) bFwd.getDrawable();
        ImageView bHome = makeBtn(GlyphIcon.HOME, iconPad, "主页");
        ImageView bTabs = makeBtn(GlyphIcon.TABS, iconPad, "标签页");
        btnStar = makeBtn(GlyphIcon.STAR, iconPad, "收藏本页");
        gStar = (GlyphIcon) btnStar.getDrawable();
        ImageView bReload = makeBtn(GlyphIcon.REFRESH, iconPad, "刷新");
        ImageView bMenu = makeBtn(GlyphIcon.MENU, iconPad, "菜单");

        toolbar.addView(bBack, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bFwd, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bHome, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bTabs, new LinearLayout.LayoutParams(btnW, tbH));

        addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setTextColor(0xFFE8EDF2);
        addr.setHintTextColor(0xFF7A8794);
        addr.setHint("输入网址或搜索");
        addr.setContentDescription("地址栏");
        addr.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setImeOptions(EditorInfo.IME_ACTION_GO);
        addr.setPadding(pad, 0, pad, 0);
        addr.setSelectAllOnFocus(true);
        addr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addr.selectAll();
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C222B);
        bg.setCornerRadius(addrH / 2f);
        addr.setBackground(bg);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, addrH, 1f);
        alp.leftMargin = pad / 2;
        alp.rightMargin = pad / 2;
        toolbar.addView(addr, alp);

        toolbar.addView(btnStar, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bReload, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bMenu, new LinearLayout.LayoutParams(btnW, tbH));

        column.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, tbH));
        column.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserWebView w = activeTab();
                if (w != null) onNativeBack(w, false);
            }
        });
        bFwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserWebView w = activeTab();
                if (w != null && w.canGoForward()) w.goForward();
                updateToolbarState();
            }
        });
        bHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHomeTab();
            }
        });
        bReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserWebView w = activeTab();
                if (w != null) {
                    if (w.isHomePage()) pushHomeData();
                    else w.reload();
                }
            }
        });
        btnStar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBookmark();
            }
        });
        bTabs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTabsWithThumb(0);
            }
        });
        bMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSheet();
            }
        });

        addr.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                addrEditing = hasFocus;
                if (!hasFocus) updateToolbarState();
            }
        });
        addr.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    openFromAddressBar(addr.getText().toString());
                    return true;
                }
                return false;
            }
        });
    }

    /** 工具条按钮：统一按下反馈 + 可读 contentDescription（控件树/无障碍都能读到语义） */
    private ImageView makeBtn(int glyph, int iconPad, String desc) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new GlyphIcon(glyph, 0xFFE8EDF2));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setPadding(iconPad, iconPad, iconPad, iconPad);
        v.setClickable(true);
        v.setFocusable(false);
        v.setContentDescription(desc);
        pressable(v);
        return v;
    }

    /**
     * 回主页：只把首屏切到前台。不再销毁其它标签页、也不重新加载首屏 ——
     * 首屏自己的状态（滚到的位置、展开状态）保得住；网页标签留在栈里，切标签还能回来。
     */
    private void goHomeTab() {
        hideKeyboard();
        BrowserWebView home = homeView;
        if (home == null) return;
        if (home != activeTab()) switchToTab(home);
    }

    private void toggleBookmark() {
        BrowserWebView w = activeTab();
        if (w == null) return;
        String url = w.getUrl();
        if (url == null || url.startsWith(HOME_URL)) {
            showStatus("先打开一个网页再加收藏", 1600);
            return;
        }
        if (store.isFavorite(url)) {
            store.removeFavorite(url);
            showStatus(getString(R.string.unfavorited), 1500);
        } else if (store.addFavorite(url)) {
            showStatus(getString(R.string.favorited), 1500);
        } else {
            showStatus(getString(R.string.fav_limit, SiteStore.MAX_FAVORITES), 2000);
        }
        pushHomeData();
        updateToolbarState();
    }

    /** 地址栏内容 → 真实 URL。不是网址就当搜索词（车机上多数时候是想搜点什么） */
    String normalizeInput(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.length() == 0) return null;
        String low = s.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("file://") || low.startsWith("about:")) {
            return s;
        }
        if (!s.contains(" ") && looksLikeAddress(s)) {
            return "https://" + s;
        }
        return searchUrlFor(s);
    }

    /**
     * 像不像一个网址（不含协议）。
     *
     * 除了 example.com 这种，还得认 127.0.0.1:8099/hover.html、localhost:8080 这类
     * 调试地址 —— 之前只认「字母 TLD」，IP 和端口会被当成搜索词去搜，很反直觉。
     */
    private static boolean looksLikeAddress(String s) {
        if (s.matches("[\\w\\-.:]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?")) return true;
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?")) return true;
        if (s.matches("localhost(:\\d+)?(/.*)?")) return true;
        // 带端口的局域网名：nas:5000、router:8080
        if (s.matches("[\\w\\-]+:\\d+(/.*)?")) return true;
        return false;
    }

    /** 按设置里选的搜索引擎拼搜索地址（地址栏输入的纯文字走这里） */
    private String searchUrlFor(String q) {
        String id = store.engine();
        String prefix;
        if ("bing".equals(id)) {
            prefix = "https://cn.bing.com/search?q=";
        } else if ("sogou".equals(id)) {
            prefix = "https://www.sogou.com/web?query=";
        } else if ("google".equals(id)) {
            prefix = "https://www.google.com/search?q=";
        } else {
            prefix = "https://www.baidu.com/s?wd=";
        }
        return prefix + Uri.encode(q);
    }
    private void openFromAddressBar(String text) {
        String url = normalizeInput(text);
        if (url == null) return;
        hideKeyboard();
        addr.clearFocus();
        BrowserWebView w = activeTab();
        if (w == null) return;
        if (BrowserWebView.isDownloadableUrl(url)) {
            onDownload(w, url, w.getSettings().getUserAgentString(), null,
                    BrowserWebView.mimeOfUrl(url), -1);
        } else if (w.isHomePage()) {
            openTab(url);          // 首屏上开新标签，保住首屏
        } else {
            w.loadUrl(url);        // 网页上原地跳转（返回键还能回来）
        }
        // 关键：clearFocus() 之后 MuMu/部分车机会把焦点又还给地址栏，
        // 结果地址栏一直停在“用户刚才敲的那串字”，永远不跟着页面走。
        // 主动把焦点交给 WebView，地址栏才真正退出编辑态。
        BrowserWebView now = activeTab();
        if (now != null) now.requestFocus();
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && addr != null) {
                imm.hideSoftInputFromWindow(addr.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateToolbarState() {
        BrowserWebView w = activeTab();
        if (w == null) return;
        String url = w.getUrl();
        if (addr != null && !addrEditing) {
            if (url == null || url.startsWith(HOME_URL)) addr.setText("");
            else addr.setText(url);
        }
        if (gBack != null) gBack.setColor(w.canGoBack() ? 0xFFE8EDF2 : 0xFF4A5460);
        if (gFwd != null) gFwd.setColor(w.canGoForward() ? 0xFFE8EDF2 : 0xFF4A5460);
        if (gStar != null) {
            boolean marked = url != null && !url.startsWith(HOME_URL) && store.isFavorite(url);
            gStar.setColor(marked ? 0xFFF5C542 : 0xFFE8EDF2);
        }
    }

    /** 只负责把地址栏文字对齐到当前页真实 URL；正在输入时绝不打扰 */
    private void syncAddressBar() {
        try {
            if (addr == null || addr.hasFocus()) return;
            BrowserWebView w = activeTab();
            if (w == null) return;
            String url = w.getUrl();
            if (url == null || url.startsWith("about:") || url.startsWith("data:")) return;
            String shown = addr.getText().toString();
            if (url.startsWith(HOME_URL)) {
                if (!shown.isEmpty()) addr.setText("");
                return;
            }
            if (!url.equals(shown)) addr.setText(url);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------------------------------------------------- 标签页 -- */
    /*
     * 上游有标签栈（最多 5 个、首屏永驻栈底），但车机上没有任何入口能看到它 ——
     * 用户只能靠"返回"一层层退。这里补一个标签列表，点一下就切。
     */
    /** 缩略图是不是有内容：整屏接近纯黑（页面还没画出来）就当没抓到 */
    private static boolean thumbUsable(Bitmap b) {
        if (b == null || b.isRecycled() || b.getWidth() < 2 || b.getHeight() < 2) return false;
        long sum = 0;
        int n = 0;
        int stepX = Math.max(1, b.getWidth() / 40);
        int stepY = Math.max(1, b.getHeight() / 40);
        for (int y = 0; y < b.getHeight(); y += stepY) {
            for (int x = 0; x < b.getWidth(); x += stepX) {
                int c = b.getPixel(x, y);
                sum += (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
                n++;
            }
        }
        long avg = n > 0 ? sum / n : 0;
        return n > 0 && avg >= 40;
    }
    /**
     * 抓一张标签缩略图。只在标签可见时调用（抓的是屏幕上的硬件图层），
     * 页面被遮住或已经切走就别抓，否则截到的是遮罩。
     */
    private void captureTabThumb(final BrowserWebView w, long delayMs, final Runnable after) {
        if (w == null) {
            if (after != null) after.run();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // PixelCopy.request(Window, …) 是 API 26 起；低版本先不抓，卡片走占位图
            if (after != null) after.run();
            return;
        }
        Runnable job = new Runnable() {
            @Override public void run() {
                if (w.getWidth() <= 0 || w.getHeight() <= 0 || w.getVisibility() != View.VISIBLE) {
                    if (after != null) after.run();
                    return;
                }
                // 整页盖在上面时别抓，抓到的会是页面本身
                if (pageVisible()) {
                    if (after != null) after.run();
                    return;
                }
                // 标签已经被关掉/已经不在栈里就不抓了
                if (!tabs.contains(w)) {
                    if (after != null) after.run();
                    return;
                }
                // 同一个标签 1.2s 内只抓一次（onPageCommitVisible 和 onPageFinished 会连着来）
                Long last = thumbStamps.get(w);
                if (delayMs > 0 && last != null
                        && SystemClock.uptimeMillis() - last < 1200L) {
                    if (after != null) after.run();
                    return;
                }
                final int vw = w.getWidth();
                final int vh = w.getHeight();
                final int[] loc = new int[2];
                w.getLocationInWindow(loc);
                final Bitmap full;
                try {
                    full = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
                } catch (Throwable t) {
                    if (after != null) after.run();
                    return;
                }
                try {
                    PixelCopy.request(getWindow(),
                            new Rect(loc[0], loc[1], loc[0] + vw, loc[1] + vh), full,
                            new PixelCopy.OnPixelCopyFinishedListener() {
                                @Override public void onPixelCopyFinished(int result) {
                                    if (result != PixelCopy.SUCCESS) {
                                        Log.w(TAG, "thumb copy failed result=" + result);
                                    }
                                    if (result == PixelCopy.SUCCESS) {
                                        int tw = 420;
                                        int th = Math.max(1, Math.round(tw * (float) vh / vw));
                                        Bitmap small = Bitmap.createScaledBitmap(full, tw, th, true);
                                        // 页面还在淡入/整屏纯黑时抓下来的图等于没内容，别覆盖上一张
                                        if (thumbUsable(small) && tabs.contains(w)) {
                                            Bitmap old = tabThumbs.put(w, small);
                                            thumbStamps.put(w, SystemClock.uptimeMillis());
                                            if (old != null && old != small && !old.isRecycled() && !pageVisible()) {
                                                old.recycle();
                                            }
                                        } else {
                                            small.recycle();
                                        }
                                    }
                                    if (!full.isRecycled()) full.recycle();
                                    if (after != null) after.run();
                                }
                            }, new Handler(Looper.getMainLooper()));
                } catch (Throwable t) {
                    if (!full.isRecycled()) full.recycle();
                    if (after != null) after.run();
                }
            }
        };
        if (delayMs > 0) uiHandler.postDelayed(job, delayMs);
        else uiHandler.post(job);
    }

    /** 打开标签页之前先把当前画面抓下来，卡片里的缩略图才是最新的 */
    private void showTabsWithThumb(long delayMs) {
        captureTabThumb(activeTab(), delayMs, new Runnable() {
            @Override public void run() {
                showTabs();
            }
        });
    }
    private void showTabs() {
        final BrowserWebView[] arr = tabs.toArray(new BrowserWebView[0]);
        LinearLayout body = openPage("标签页 · " + arr.length, "新建", new View.OnClickListener() {
            @Override public void onClick(View v) {
                closePage();
                openTab(HOME_URL);
            }
        });
        if (arr.length == 0) {
            body.addView(pageEmpty("没有打开的标签页", "点右上角「新建」开一个"));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < arr.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(0.2f), 1f);
            lp.rightMargin = (i % 3 == 2) ? 0 : px(0.008f);
            lp.bottomMargin = px(0.008f);
            row.addView(makeTabCard(arr[i], arr[i] == activeTab()), lp);
        }
        int rem = arr.length % 3;
        if (rem != 0 && row != null) {
            for (int i = rem; i < 3; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }

        // 标签多了以后，一个个点 ✕ 太慢：给一个「关闭其他」的兜底
        if (arr.length > 1) {
            TextView closeOthers = new TextView(this);
            closeOthers.setText("关闭其他标签页");
            closeOthers.setTextColor(0xFF8B95A5);
            closeOthers.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
            closeOthers.setGravity(Gravity.CENTER);
            closeOthers.setPadding(0, px(0.022f), 0, px(0.022f));
            pressable(closeOthers);
            closeOthers.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    askConfirm("关闭其他标签页", "只保留当前显示的这一页（首屏保留）。", "关闭", new Runnable() {
                        @Override public void run() {
                            BrowserWebView keep = activeTab();
                            boolean keepIsHome = keep != null && keep.isHomePage();
                            // 留下的本身就是首屏时，由它接管 homeView
                            if (keepIsHome) homeView = keep;
                            java.util.ArrayList<BrowserWebView> others = new java.util.ArrayList<>();
                            for (BrowserWebView w : tabs) {
                                if (w == keep) continue;
                                if (!keepIsHome && w == homeView) continue;
                                others.add(w);
                            }
                            for (BrowserWebView w : others) destroyTab(w);
                            showTabs();
                        }
                    });
                }
            });
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            closeLp.topMargin = px(0.02f);
            body.addView(closeOthers, closeLp);
        }
    }

    /** 标签卡片：标题 + 网址 + 右上角关闭 */
    private View makeTabCard(final BrowserWebView w, boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = px(0.012f);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0xFF1B2430 : 0xFF161B22);
        bg.setCornerRadius(px(0.012f));
        bg.setStroke(Math.max(2, px(2f / 2880f)), active ? 0xFF3F80C0 : 0xFF232B35);
        card.setBackground(bg);
        pressable(card);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                switchToTab(w);
                closePage();
            }
        });

        String url = w.getUrl();
        String title = w.getTitle();
        if (url == null || url.startsWith(HOME_URL)) {
            title = "首屏";
            url = "本地页面";
        } else if (title == null || title.trim().isEmpty()) {
            title = url;
        }

        // 缩略图：抓过的直接显示；没抓过就给一块深色占位 + 站点首字
        int thumbH = px(0.12f);
        Bitmap thumb = tabThumbs.get(w);
        if (thumb != null && !thumb.isRecycled()) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(thumb);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(0xFF0E1218);
            ph.setCornerRadius(px(0.008f));
            iv.setBackground(ph);
            card.addView(iv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, thumbH));
        } else {
            TextView ph = new TextView(this);
            String letter = (title != null && title.length() > 0)
                    ? title.substring(0, 1).toUpperCase() : "·";
            ph.setText(letter);
            ph.setTextColor(0xFF313D4A);
            ph.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(96f / 2880f));
            ph.setGravity(Gravity.CENTER);
            GradientDrawable phBg = new GradientDrawable();
            phBg.setColor(0xFF0E1218);
            phBg.setCornerRadius(px(0.008f));
            ph.setBackground(phBg);
            card.addView(ph, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, thumbH));
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        top.setPadding(0, px(0.008f), 0, 0);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE6E9F0);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32f / 2880f));
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView close = new ImageView(this);
        close.setImageDrawable(new GlyphIcon(GlyphIcon.CLOSE, 0xFF8B95A5));
        int cs = px(0.038f);
        close.setContentDescription("关闭标签：" + title);
        close.setPadding(cs / 4, cs / 6, cs / 6, cs / 6);
        pressable(close);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeTabView(w);
            }
        });
        top.addView(close, new LinearLayout.LayoutParams(cs, cs));
        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView u = new TextView(this);
        u.setText(url);
        u.setTextColor(0xFF6E7A88);
        u.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24f / 2880f));
        u.setSingleLine(true);
        u.setEllipsize(android.text.TextUtils.TruncateAt.END);
        u.setPadding(0, px(0.006f), 0, 0);
        card.addView(u);
        return card;
    }

    /** 统一关标签：摘栈、移除视图、回收缩略图、销毁 WebView；活动标签会自动落到剩下的最后一个 */
    private void destroyTab(BrowserWebView w) {
        if (w == null) return;
        boolean wasActive = (w == activeTab());
        tabs.remove(w);
        host.removeView(w);
        thumbStamps.remove(w);
        Bitmap dead = tabThumbs.remove(w);
        // 标签页正开着时卡片上的 ImageView 还在用这张图，先别 recycle
        if (dead != null && !dead.isRecycled() && !pageVisible()) dead.recycle();
        if (activeWeb == w) activeWeb = tabs.peekLast();
        try {
            w.destroy();
        } catch (Throwable ignored) {
        }
        if (wasActive) {
            BrowserWebView next = activeTab();
            if (next != null) {
                next.setVisibility(View.VISIBLE);
                next.onResume();
                next.requestFocus();
                next.resetBackPress();
            }
        }
    }

    /** 关掉某个标签页（不是只有栈顶那个） */
    private void closeTabView(BrowserWebView w) {
        if (w == null) return;
        if (w == homeView) {
            showStatus("首屏不能关闭", 1400);
            return;
        }
        destroyTab(w);
        updateToolbarState();
        showTabs();
    }

    /** 切到某个标签页：只改显示，不动标签顺序（首屏因此可以放心切到前面） */
    private void switchToTab(BrowserWebView target) {
        if (target == null || target == activeTab()) return;
        BrowserWebView cur = activeTab();
        if (cur != null) {
            cur.onPause();
            cur.setVisibility(View.GONE);
        }
        activeWeb = target;
        target.setVisibility(View.VISIBLE);
        target.onResume();
        target.requestFocus();
        target.resetBackPress();
        if (target.isHomePage()) pushHomeData();
        updateToolbarState();
    }

    /* ------------------------------------------------------------ 菜单 -- */

    private void showMenu() {
        BrowserWebView w = activeTab();
        final boolean desktop = (w == null) || w.isDesktopUa();
        final String[] items = new String[]{
                desktop ? "切换为手机版网页" : "切换为桌面版网页",
                "搜索引擎",
                "刷新页面",
                "清除缓存",
                "关于"
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        BrowserWebView cur = activeTab();
                        if (which == 0) {
                            if (cur != null) cur.setDesktopUa(!desktop);
                        } else if (which == 1) {
                            showEnginePicker();
                        } else if (which == 2) {
                            if (cur != null) cur.reload();
                        } else if (which == 3) {
                            if (cur != null) {
                                cur.clearCache(true);
                                showStatus("缓存已清除", 1600);
                            }
                        } else {
                            showAbout();
                        }
                    }
                })
                .show();
    }

    /* ------------------------------------------------------ 自动更新 -- */
    /*
     * 6.10：仓库公开后，检查更新走 GitHub Release API —— 匿名可读，APK 里不含任何 token。
     * 链路：检查 → DownloadManager 下载 → 校验 sha256 与大小 → 系统安装器。
     * 任何一步失败都给出可读原因，不伪装成功；旧源 dl.z736.vip 仍然永久停用。
     */

    private TextView updateStatusView;
    private TextView updateDetailView;
    private UpdateChecker.Result updateResult;
    private boolean updateChecking = false;
    private String updateStatusText = "未检查";
    private String updateErrorText = "";
    private long pendingDownloadId = -1L;
    private String pendingSha = "";
    private long pendingSize = 0L;
    private String pendingVersion = "";
    private BroadcastReceiver downloadReceiver;

    private TextView updateActionButton(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF9CCBFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(34f / 2880f));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(px(0.036f));
        b.setMinimumWidth(px(0.09f));
        b.setPadding(px(0.016f), px(0.009f), px(0.016f), px(0.009f));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C2733);
        bg.setCornerRadius(px(0.012f));
        b.setBackground(bg);
        b.setContentDescription(text);   // 语义标签，控件树/无障碍都能读到
        pressable(b);
        b.setOnClickListener(click);
        return b;
    }

    /** 下载完成广播：只在 Activity 存活期间注册，避免进程被杀后残留。 */
    private void registerUpdateReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (pendingDownloadId <= 0 || id != pendingDownloadId) return;
                finishUpdateDownload(id);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // 下载完成广播由系统 download provider 发出，必须用 EXPORTED 才收得到
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(downloadReceiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "注册下载广播失败", t);
            downloadReceiver = null;
        }
    }

    private void unregisterUpdateReceiver() {
        if (downloadReceiver == null) return;
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Throwable ignored) {
        }
        downloadReceiver = null;
    }

    /** 重进应用时续查一次待安装的下载：可能下载在后台已经完成。 */
    private void resumePendingUpdate() {
        if (pendingDownloadId <= 0) return;
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (pendingDownloadId > 0) finishUpdateDownload(pendingDownloadId);
            }
        }, 800L);
    }

    /** 工作线程请求 API，结果回主线程；不阻塞 UI。 */
    private void checkForUpdate(final boolean manual) {
        if (updateChecking) return;
        updateChecking = true;
        updateErrorText = "";
        updateStatusText = "正在检查更新…";
        refreshUpdateCard();
        if (manual) showStatus("正在检查更新…", 1800);
        new Thread(new Runnable() {
            @Override public void run() {
                UpdateChecker.Result fetched = null;
                String error = "";
                try {
                    fetched = UpdateChecker.fetchLatest();
                } catch (Throwable t) {
                    error = (t.getMessage() == null || t.getMessage().isEmpty())
                            ? t.getClass().getSimpleName() : t.getMessage();
                }
                final UpdateChecker.Result result = fetched;
                final String err = error;
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        updateChecking = false;
                        if (result != null) {
                            updateResult = result;
                            updateStatusText = result.isNewerThan(BuildConfig.VERSION_NAME)
                                    ? "发现新版本 " + result.versionName
                                    : "已是最新 " + BuildConfig.VERSION_NAME;
                            updateErrorText = "";
                            if (manual) showStatus(updateStatusText, 2200);
                        } else {
                            updateResult = null;
                            updateStatusText = "检查失败";
                            updateErrorText = err;
                            if (manual) showStatus("检查更新失败：" + err, 3200);
                        }
                        refreshUpdateCard();
                    }
                });
            }
        }, "update-check").start();
    }

    /** 只刷新关于页卡片；别的页面（或卡片已被换掉）时什么都不做。 */
    private void refreshUpdateCard() {
        if (updateStatusView == null || !pageVisible()) return;
        int color = 0xFFD8E0E8;
        if (!updateErrorText.isEmpty()) {
            color = 0xFFFFC46B;
        } else if (updateResult != null && updateResult.isNewerThan(BuildConfig.VERSION_NAME)) {
            color = 0xFF7DDC9A;
        }
        updateStatusView.setText(updateStatusText);
        updateStatusView.setTextColor(color);
        if (updateDetailView == null) return;
        String detail;
        if (!updateErrorText.isEmpty()) {
            detail = "原因：" + updateErrorText + "\n可重试检查，或打开发布页手动下载。";
        } else if (updateResult == null) {
            detail = "检查 GitHub Release 的最新版本；下载后先校验 sha256，再交给系统安装器。";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(updateResult.apkName);
            if (updateResult.size > 0) sb.append(" · ").append(mb(updateResult.size));
            if (!updateResult.sha256.isEmpty()) sb.append(" · SHA ").append(shortSha(updateResult.sha256));
            sb.append('\n');
            sb.append(updateResult.isNewerThan(BuildConfig.VERSION_NAME)
                    ? "点「下载并安装」直接更新。" : "已是最新；如需修复安装可重新下载。");
            detail = sb.toString();
        }
        updateDetailView.setText(detail);
    }

    private String shortSha(String sha) {
        if (sha == null) return "";
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    /** 入队下载：目标放在应用私有下载目录，完成广播里再校验。 */
    private void startUpdateDownload() {
        if (updateResult == null) {
            showStatus("请先检查更新", 1600);
            return;
        }
        if (pendingDownloadId > 0) {
            showStatus("已有一次下载在进行", 1800);
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) {
            showStatus("系统下载服务不可用", 2200);
            return;
        }
        try {
            DownloadManager.Request req =
                    new DownloadManager.Request(Uri.parse(updateResult.apkUrl));
            req.setMimeType("application/vnd.android.package-archive");
            req.addRequestHeader("Accept", "application/octet-stream");
            req.setTitle("车览浏览器 " + updateResult.versionName);
            req.setDescription("正在下载更新");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                    updateResult.apkName);
            pendingDownloadId = dm.enqueue(req);
            pendingSha = updateResult.sha256 == null ? "" : updateResult.sha256;
            pendingSize = updateResult.size;
            pendingVersion = updateResult.versionName;
            saveUiState();
            updateStatusText = "正在下载 " + updateResult.versionName + "…";
            updateErrorText = "";
            refreshUpdateCard();
            showStatus("开始下载 " + updateResult.versionName, 2200);
        } catch (Throwable t) {
            pendingDownloadId = -1L;
            saveUiState();
            showStatus("下载失败：" + t.getMessage(), 2800);
        }
    }

    /** 下载完成：先看状态，再校验大小与 sha256，最后交给安装器。 */
    private void finishUpdateDownload(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        int status = DownloadManager.STATUS_FAILED;
        long bytes = 0L;
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                bytes = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        // 还在下就别当失败：重进应用时的续查会走到这里
        if (status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_PAUSED) {
            updateStatusText = "正在下载 " + (pendingVersion == null ? "" : pendingVersion) + "…";
            updateErrorText = "";
            refreshUpdateCard();
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            clearPendingDownload();
            updateStatusText = "下载失败";
            updateErrorText = "下载未完成（已 " + mb(bytes) + "）";
            refreshUpdateCard();
            showStatus("更新下载失败，可重试", 2600);
            return;
        }
        String actual = sha256OfDownload(dm, id);
        String expected = pendingSha == null ? "" : pendingSha;
        if (!expected.isEmpty() && (actual == null || !expected.equalsIgnoreCase(actual))) {
            clearPendingDownload();
            try {
                dm.remove(id);
            } catch (Throwable ignored) {
            }
            updateStatusText = "校验失败";
            updateErrorText = "sha256 与发布清单不一致，已丢弃这次下载";
            refreshUpdateCard();
            showStatus("更新包校验失败，已丢弃", 3000);
            return;
        }
        if (pendingSize > 0 && actual != null) {
            long real = 0L;
            Cursor c2 = null;
            try {
                c2 = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c2 != null && c2.moveToFirst()) {
                    real = c2.getLong(c2.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                }
            } catch (Throwable ignored) {
            } finally {
                if (c2 != null) c2.close();
            }
            if (real > 0 && real != pendingSize) {
                clearPendingDownload();
                updateStatusText = "校验失败";
                updateErrorText = "文件大小 " + mb(real) + " 与发布信息不一致";
                refreshUpdateCard();
                showStatus("更新包大小不符，已丢弃", 3000);
                return;
            }
        }
        installDownloadedApk(dm, id, actual);
    }

    private String sha256OfDownload(DownloadManager dm, long id) {
        android.os.ParcelFileDescriptor pfd = null;
        InputStream in = null;
        try {
            pfd = dm.openDownloadedFile(id);
            in = new java.io.FileInputStream(pfd.getFileDescriptor());
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "计算更新包哈希失败", t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void installDownloadedApk(final DownloadManager dm, final long id, String sha) {
        // canRequestPackageInstalls 是 API 26 起；API 21-25 直接走安装器
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            updateStatusText = "需要允许安装应用";
            updateErrorText = "";
            refreshUpdateCard();
            askConfirm("允许安装更新",
                    "系统要求先允许「车览浏览器」安装应用。点「去设置」打开开关，返回后再点一次安装。",
                    "去设置", new Runnable() {
                        @Override public void run() {
                            try {
                                Intent i = new Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName()));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            } catch (Throwable t) {
                                try {
                                    startActivity(new Intent(
                                            android.provider.Settings.ACTION_SECURITY_SETTINGS));
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    });
            return;
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            showStatus("拿不到已下载的文件", 2400);
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "application/vnd.android.package-archive");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
            updateStatusText = "已交给系统安装器";
            updateErrorText = "";
            refreshUpdateCard();
            Log.i(TAG, "更新包已交给安装器 sha=" + shortSha(sha));
            clearPendingDownload();
        } catch (Throwable t) {
            showStatus("无法启动安装器：" + t.getMessage(), 3000);
        }
    }

    private void clearPendingDownload() {
        pendingDownloadId = -1L;
        pendingSha = "";
        pendingSize = 0L;
        pendingVersion = "";
        saveUiState();
    }

    private void copyReleasePageLink() {
        boolean ok = false;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        "车览浏览器发布页", UpdateChecker.RELEASE_PAGE_URL));
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        showStatus(ok ? "发布页链接已复制" : "复制发布页链接失败", 2600);
    }

    private void openReleasePage() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            copyReleasePageLink();
        }
    }

    private void showAbout() {
        String engine = "";
        try {
            PackageInfo info = WebView.getCurrentWebViewPackage();
            engine = info == null ? "" : info.versionName;
        } catch (Throwable ignored) {
        }

        LinearLayout body = openPage("关于", null, null);
        TextView name = new TextView(this);
        name.setText("车览浏览器");
        name.setTextColor(0xFFF2F4F8);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(56f / 2880f));
        name.setPadding(0, px(0.014f), 0, 0);
        body.addView(name);

        TextView version = new TextView(this);
        version.setText("版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）");
        version.setTextColor(0xFFAAB7C5);
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        version.setPadding(0, px(0.003f), 0, px(0.016f));
        body.addView(version);

        body.addView(pageLabel("更新"));
        LinearLayout release = new LinearLayout(this);
        release.setOrientation(LinearLayout.VERTICAL);
        release.setPadding(px(0.012f), px(0.01f), px(0.012f), px(0.012f));
        GradientDrawable releaseBg = new GradientDrawable();
        releaseBg.setColor(0xFF151C24);
        releaseBg.setCornerRadius(px(0.004f));
        release.setBackground(releaseBg);

        TextView title = new TextView(this);
        title.setText("GitHub Release 自动更新");
        title.setTextColor(0xFFE5EBF0);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(36f / 2880f));
        release.addView(title);

        updateStatusView = new TextView(this);
        updateStatusView.setText(updateStatusText);
        updateStatusView.setTextColor(0xFFD8E0E8);
        updateStatusView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32f / 2880f));
        updateStatusView.setPadding(0, px(0.004f), 0, 0);
        release.addView(updateStatusView);

        updateDetailView = new TextView(this);
        updateDetailView.setText("");
        updateDetailView.setTextColor(0xFF9AA8B7);
        updateDetailView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        updateDetailView.setLineSpacing(px(0.003f), 1f);
        updateDetailView.setPadding(0, px(0.004f), 0, px(0.008f));
        release.addView(updateDetailView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.rightMargin = px(0.008f);
        actions.addView(updateActionButton("检查更新", new View.OnClickListener() {
            @Override public void onClick(View v) { checkForUpdate(true); }
        }), actionLp);
        String installLabel = (updateResult != null
                && updateResult.isNewerThan(BuildConfig.VERSION_NAME))
                ? "下载并安装" : "重新下载安装";
        actions.addView(updateActionButton(installLabel, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (updateResult == null) {
                    checkForUpdate(true);
                } else {
                    startUpdateDownload();
                }
            }
        }), actionLp);
        actions.addView(updateActionButton("打开发布页", new View.OnClickListener() {
            @Override public void onClick(View v) { openReleasePage(); }
        }), actionLp);
        actions.addView(updateActionButton("复制链接", new View.OnClickListener() {
            @Override public void onClick(View v) { copyReleasePageLink(); }
        }), actionLp);
        release.addView(actions);
        body.addView(release);
        refreshUpdateCard();

        body.addView(pageLabel("运行环境"));
        body.addView(pageRow("Android System WebView", engine, null, false, null, null));
    }

    /** 多行说明文字（UA 这种一行放不下的） */
    private View pageNote(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFAAB7C5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        t.setLineSpacing(px(8f / 2880f), 1f);
        t.setPadding(px(0.01f), px(0.006f), px(0.01f), px(0.02f));
        return t;
    }

    /* ------------------------------------------------------------ 下载 -- */
    private static final int REQ_DOWNLOAD_TREE = 0x5D1;
    private final java.util.LinkedHashMap<String, DownloadRowViews> downloadRows = new java.util.LinkedHashMap<>();
    private String downloadIds = "";
    private static final class DownloadRowViews {
        TextView name, state, action;
        android.widget.ProgressBar progress;
    }

    @Override
    public void onDownload(BrowserWebView web, String url, String userAgent,
                           String contentDisposition, String mimeType, long contentLength) {
        try {
            DownloadCenter.Task t = DownloadCenter.get(this).enqueue(url, userAgent, null,
                    contentDisposition, mimeType, contentLength);
            showStatus("开始下载：" + t.name, 2400);
        } catch (Throwable t) {
            showStatus(t.getMessage() == null ? "无法开始下载，请重试" : t.getMessage(), 3200);
        }
    }

    private String taskIds(java.util.List<DownloadCenter.Task> list) {
        StringBuilder ids = new StringBuilder();
        for (DownloadCenter.Task t : list) ids.append(t.id).append(';');
        return ids.toString();
    }

    private void showDownloads() {
        final DownloadCenter center = DownloadCenter.get(this);
        java.util.List<DownloadCenter.Task> list = center.tasks();
        LinearLayout body = openPage("下载 · " + list.size(), "打开目录", v -> openDownloadFolder(null));
        downloadRows.clear();
        downloadIds = taskIds(list);
        body.addView(pageLabel("保存位置"));
        body.addView(pageRow(center.destinationLabel(this), center.destinationDetail(this), "更改", false,
                v -> showDownloadDestination(), null));
        if (list.isEmpty()) {
            body.addView(pageEmpty("还没有下载", "在网页中点下载链接，完成后可在这里打开"));
            return;
        }
        body.addView(pageLabel("文件"));
        for (DownloadCenter.Task t : list) body.addView(downloadRow(t));
        body.addView(pageRow("清除已结束的记录", "保留文件，仅清除列表记录", "清除", false,
                v -> askConfirm("清除下载记录", "仅清除已结束的记录，文件会保留。", "清除", () -> {
                    center.clearFinished();
                    showStatus("记录已清除，文件已保留", 2000);
                }), null));
    }

    private View downloadRow(final DownloadCenter.Task t) {
        DownloadRowViews h = new DownloadRowViews();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(0.012f), px(0.012f), px(0.008f), px(0.012f));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF141A21); bg.setCornerRadius(px(0.009f)); row.setBackground(bg);
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL);
        h.name = new TextView(this); h.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38f / 2880f));
        h.name.setTextColor(0xFFE6E9F0); h.name.setMaxLines(2); h.name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        text.addView(h.name);
        h.state = new TextView(this); h.state.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        h.state.setMaxLines(2); h.state.setPadding(0, px(0.004f), 0, px(0.004f)); text.addView(h.state);
        h.progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        h.progress.setMax(1000); h.progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF77B8E8));
        text.addView(h.progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(0.004f)));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        h.action = updateActionButton("打开", v -> onDownloadTap(t));
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(px(0.075f), px(0.045f)); actionLp.leftMargin = px(0.012f);
        row.addView(h.action, actionLp);
        TextView more = updateActionButton("更多", v -> showDownloadMenu(t)); more.setContentDescription("更多：" + t.name);
        more.setPadding(px(0.008f),px(0.009f),px(0.008f),px(0.009f));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(px(0.055f), px(0.045f)); moreLp.leftMargin = px(0.009f); row.addView(more, moreLp);
        row.setOnClickListener(v -> { if(t.finished()) openDownloadedFile(t); else showDownloadMenu(t); });
        row.setOnLongClickListener(v -> { showDownloadMenu(t); return true; });
        downloadRows.put(t.id, h); bindDownload(t, h);
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.addView(row);
        wrap.addView(new View(this), new LinearLayout.LayoutParams(1, px(0.008f))); return wrap;
    }

    private void bindDownload(DownloadCenter.Task t, DownloadRowViews h) {
        h.name.setText(t.name); h.state.setText(DownloadCenter.stateLine(t));
        h.state.setTextColor(t.status == DownloadCenter.ST_FAILED ? 0xFFF09494 : 0xFFAFBBC8);
        String action = t.finished() ? "打开" : t.active() ? "取消" : t.status == DownloadCenter.ST_DELETING ? "删除中" : "重试";
        h.action.setText(action); h.action.setContentDescription(action + "：" + t.name);
        h.action.setEnabled(t.status != DownloadCenter.ST_DELETING);
        h.progress.setVisibility(t.active() ? View.VISIBLE : View.GONE);
        h.progress.setIndeterminate(t.total <= 0);
        if(t.total > 0) h.progress.setProgress((int)Math.min(1000, t.done * 1000 / t.total));
    }

    private void onDownloadTap(DownloadCenter.Task t) {
        if(t.finished()) openDownloadedFile(t);
        else if(t.active()) DownloadCenter.get(this).cancel(t.id);
        else if(t.status != DownloadCenter.ST_DELETING) DownloadCenter.get(this).retry(t.id);
    }

    private void openDownloadedFile(DownloadCenter.Task t) {
        DownloadCenter center = DownloadCenter.get(this);
        if(!center.fileExists(t)) { showStatus("文件不存在或文件夹授权已失效，可在更多中移除记录", 3200); return; }
        if("application/vnd.android.package-archive".equals(DownloadCenter.mimeFor(t.name,t.mime))) {
            installDownloadedApk(center,t); return;
        }
        if(center.openFile(this,t)) return;
        showChoiceDialog("没有可用的查看器", new String[]{"分享文件", "复制文件位置"}, which -> {
            if(which == 0) { if(!center.shareFile(this,t)) showStatus("没有可分享的应用",2400); }
            else { DownloadCenter.copyText(this,"文件位置",center.fileLocation(t)); showStatus("文件位置已复制",2000); }
        });
    }

    private void installDownloadedApk(final DownloadCenter center, final DownloadCenter.Task t) {
        if(Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            askConfirm("允许安装应用", "请在系统设置允许安装应用，返回后再次点击打开。", "去设置", () -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()))); }
                catch(Throwable e) { showStatus("系统未提供此设置，请使用文件管理器打开 APK",3200); }
            }); return;
        }
        if(!center.openFile(this,t)) showStatus("系统没有可用的安装器，可在更多中分享 APK",2800);
    }

    private void openDownloadFolder(DownloadCenter.Task t) {
        DownloadCenter center = DownloadCenter.get(this);
        int dest = t == null ? center.destination() : t.dest;
        if(dest == DownloadCenter.DEST_PRIVATE) { showPrivateDownloads(); return; }
        boolean opened = t == null ? center.openFolder(this) : center.openFolder(this,t);
        if(!opened) {
            String path = t == null ? center.destinationDetail(this) : center.fileLocation(t);
            DownloadCenter.copyText(this,"保存位置",path); showStatus("没有可用的文件管理器，保存位置已复制",2800);
        }
    }

    private void showPrivateDownloads() {
        DownloadCenter center = DownloadCenter.get(this);
        LinearLayout body = openPage("应用内文件",null,null);
        File[] files = center.privateDir().listFiles(file -> file.isFile());
        if(files == null || files.length == 0) { body.addView(pageEmpty("目录里没有文件", "这里显示保存在应用内的下载文件")); return; }
        java.util.Arrays.sort(files,(a,b) -> Long.compare(b.lastModified(),a.lastModified()));
        for(File file : files) {
            DownloadCenter.Task item = new DownloadCenter.Task(); item.id = "local:"+file.getName(); item.name = file.getName();
            item.uri = file.getAbsolutePath(); item.dest = DownloadCenter.DEST_PRIVATE; item.status = DownloadCenter.ST_DONE;
            item.mime = DownloadCenter.mimeFor(item.name,null); item.done = file.length(); item.total = item.done;
            body.addView(pageRow(item.name, DownloadCenter.human(item.done),"打开",false,v -> openDownloadedFile(item),null));
        }
    }

    private void showDownloadMenu(final DownloadCenter.Task t) {
        DownloadCenter center = DownloadCenter.get(this);
        java.util.List<String> labels = new java.util.ArrayList<>(); java.util.List<Runnable> actions = new java.util.ArrayList<>();
        if(t.finished()) {
            labels.add("打开文件"); actions.add(() -> openDownloadedFile(t));
            labels.add("分享文件"); actions.add(() -> { if(!center.shareFile(this,t)) showStatus("没有可分享的应用",2200); });
            labels.add("打开所在目录"); actions.add(() -> openDownloadFolder(t));
            labels.add("复制文件位置"); actions.add(() -> { DownloadCenter.copyText(this,"文件位置",center.fileLocation(t));showStatus("文件位置已复制",1800); });
        } else if(t.active()) { labels.add("取消下载"); actions.add(() -> center.cancel(t.id)); }
        else if(t.status != DownloadCenter.ST_DELETING) { labels.add("重试下载"); actions.add(() -> center.retry(t.id)); }
        if(!t.active()) { labels.add("移除记录，保留文件"); actions.add(() -> center.forget(t.id)); }
        labels.add("删除文件和记录"); actions.add(() -> askConfirm("删除文件", "删除“"+t.name+"”及其记录？", "删除", () -> center.remove(t.id)));
        showChoiceDialog(t.name,labels.toArray(new String[0]),which -> actions.get(which).run());
    }

    private void showDownloadDestination() {
        DownloadCenter center = DownloadCenter.get(this);
        String publicLabel = Build.VERSION.SDK_INT >= 29 ? "系统下载目录" : "应用内目录";
        showChoiceDialog("下载保存位置",new String[]{publicLabel,"应用内目录","选择其他文件夹…"},which -> {
            if(which == 2) { pickDownloadFolder(); return; }
            center.setDestination(which == 0 && Build.VERSION.SDK_INT >= 29 ? DownloadCenter.DEST_PUBLIC : DownloadCenter.DEST_PRIVATE);
            afterDestinationChanged();
        });
    }

    private void pickDownloadFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    |Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            String tree = DownloadCenter.get(this).treeUri();
            if(!tree.isEmpty() && Build.VERSION.SDK_INT >= 26) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(tree));
            startActivityForResult(i,REQ_DOWNLOAD_TREE);
        } catch(Throwable e) { showStatus("系统没有文件夹选择器，可使用系统下载目录",2800); }
    }

    private void afterDestinationChanged() {
        if("设置".equals(currentPageRoute)) showSettings();
        else if("下载".equals(currentPageRoute)) showDownloads();
        showStatus("保存位置已更改",1800);
    }

    @Override
    protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode != REQ_DOWNLOAD_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException();
            getContentResolver().takePersistableUriPermission(tree,flags);
            DownloadCenter center = DownloadCenter.get(this); center.setTreeUri(tree.toString());center.setDestination(DownloadCenter.DEST_TREE);
            afterDestinationChanged();
        } catch(Throwable e) { showStatus("未获得文件夹写入权限，保存位置保持原样",2800); }
    }

    private void onDownloadsChanged() {
        if(!pageVisible() || !"下载".equals(currentPageRoute)) return;
        java.util.List<DownloadCenter.Task> list = DownloadCenter.get(this).tasks();
        if(!taskIds(list).equals(downloadIds)) { showDownloads(); return; }
        for(DownloadCenter.Task t : list) { DownloadRowViews h = downloadRows.get(t.id); if(h != null) bindDownload(t,h); }
    }

    private void showChoiceDialog(String title,String[] labels,java.util.function.IntConsumer action) {
        int pad = px(0.016f);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);box.setPadding(pad,pad,pad,pad);
        GradientDrawable bg = new GradientDrawable();bg.setColor(0xFF18212B);bg.setCornerRadius(px(0.012f));box.setBackground(bg);
        TextView heading = new TextView(this);heading.setText(title);heading.setTextColor(0xFFF2F4F8);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(42f/2880f));heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);heading.setPadding(px(0.009f),0,0,px(0.012f));box.addView(heading);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout choices = new LinearLayout(this);choices.setOrientation(LinearLayout.VERTICAL);scroll.addView(choices);
        int rowHeight = px(0.047f);
        box.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Math.min(labels.length,6)*rowHeight));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box).create();
        for(int i=0;i<labels.length;i++) {
            final int index = i;TextView row = new TextView(this);row.setText(labels[i]);row.setContentDescription(labels[i]);
            row.setTextColor(0xFFE4ECF3);row.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(36f/2880f));row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(0.012f),0,px(0.012f),0);pressable(row);row.setOnClickListener(v -> { dialog.dismiss();action.accept(index); });
            choices.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,rowHeight));
        }
        TextView cancel = updateActionButton("取消",v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,px(0.042f));cancelLp.topMargin = px(0.009f);box.addView(cancel,cancelLp);
        dialog.show();styleDialog(dialog,1520f);
    }

    /* ------------------------------------------------- 功能页（底部面板） -- */
    /*
     * 原来点菜单是一个竖排 AlertDialog 列表，又窄又丑。
     * 改成参考 Via 的做法：从底部升起一页功能网格 —— 图标 + 文字、5 列两行，
     * 底部有分页圆点、电源键和翻页箭头。目标大、扫一眼就能点到。
     */

    private FrameLayout sheetLayer;
    private View sheetScrim;
    private LinearLayout sheetPanel;
    private View sheetPage1;
    private View sheetPage2;
    private View sheetDot1;
    private View sheetDot2;
    private ImageView sheetArrow;
    private int sheetPageIndex = 0;
    private boolean nightMode = false;
    private boolean toolbarHidden = false;
    private boolean noImageMode = false;
    private LinearLayout sheetTrack;   // 两页并排，靠 translationX 拖动
    private SheetPager sheetPager;

    private static final String[][] SHEET_PAGE1 = {
            {"新建标签", "7", "newtab"},
            {"标签页", "7", "tabs"},
            {"书签", "4", "bookmarks"},
            {"历史", "10", "history"},
            {"下载", "8", "download"},
            {"分享", "11", "share"},
            {"设置", "17", "settings"},
            {"关于", "5", "about"},
            {"全屏", "14", "fullscreen"},
            {"退出", "16", "exit"}
    };

    private static final String[][] SHEET_PAGE2 = {
            {"页内查找", "9", "find"},
            {"阅读模式", "5", "reader"},
            {"夜间模式", "13", "night"},
            {"无图模式", "12", "noimage"},
            {"电脑模式", "12", "desktop"},
            {"清除缓存", "15", "clear"},
            {"广告过滤", "17", "adblock"},
            {"清除数据", "15", "cleardata"},
            {"检查更新", "2", "updates"},
            {"关闭标签", "6", "closetab"}
    };

    private void buildSheet() {
        sheetLayer = new FrameLayout(this);
        sheetLayer.setVisibility(View.GONE);

        sheetScrim = new View(this);
        sheetScrim.setBackgroundColor(0xC8000000);
        sheetScrim.setAlpha(0f);
        sheetScrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideSheet();
            }
        });
        sheetLayer.addView(sheetScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sheetPanel = new LinearLayout(this);
        sheetPanel.setOrientation(LinearLayout.VERTICAL);
        int pad = px(0.012f);
        float r = px(0.016f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF14181F);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheetPanel.setBackground(bg);
        sheetPanel.setPadding(pad, pad, pad, pad / 2);

        // 两页并排放在一条轨道上：左右滑动 = 翻页，圆点/箭头切的是同一条轨道。
        // 每页必须是「一整屏宽」，否则两页会同时挤在可视区里（之前就是这么翻车的）。
        int pageW = px(1f) - pad * 2;
        sheetTrack = new LinearLayout(this);
        sheetTrack.setOrientation(LinearLayout.HORIZONTAL);
        sheetPage1 = buildSheetPage(SHEET_PAGE1);
        sheetPage2 = buildSheetPage(SHEET_PAGE2);
        sheetTrack.addView(sheetPage1, new LinearLayout.LayoutParams(
                pageW, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetTrack.addView(sheetPage2, new LinearLayout.LayoutParams(
                pageW, ViewGroup.LayoutParams.WRAP_CONTENT));

        sheetPager = new SheetPager(this);
        sheetPager.addView(sheetTrack, new FrameLayout.LayoutParams(
                pageW * 2, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetPanel.addView(sheetPager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad / 2, pad / 4, pad / 2, pad / 4);

        int btn = px(0.038f);
        ImageView closeSheet = new ImageView(this);
        closeSheet.setImageDrawable(new GlyphIcon(GlyphIcon.CLOSE, 0xFF9AA6B4));
        closeSheet.setContentDescription("关闭功能页");
        closeSheet.setPadding(btn / 4, btn / 4, btn / 4, btn / 4);
        pressable(closeSheet);
        closeSheet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideSheet(); }
        });
        bar.addView(closeSheet, new LinearLayout.LayoutParams(btn, btn));

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        // 圆点本身只有 20px，手指点不中；给整块 0.2 屏宽 × 109px 的横条当热区
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(px(0.2f), btn);
        bar.addView(dots, dotsLp);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        int ds = px(0.007f);
        int dm = px(0.005f);
        // A wide accessible target makes the two dots practical on a touch screen.
        dots.setContentDescription("切换功能页");
        pressable(dots);
        dots.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchSheetPage(); }
        });

        sheetDot1 = makeDot(true);
        sheetDot2 = makeDot(false);
        LinearLayout.LayoutParams d1 = new LinearLayout.LayoutParams(ds, ds);
        d1.rightMargin = dm;
        dots.addView(sheetDot1, d1);
        dots.addView(sheetDot2, new LinearLayout.LayoutParams(ds, ds));

        sheetArrow = new ImageView(this);
        sheetArrow.setImageDrawable(new GlyphIcon(GlyphIcon.FORWARD, 0xFF9AA6B4));
        sheetArrow.setRotation(0f);
        sheetArrow.setContentDescription("下一页");
        sheetArrow.setPadding(btn / 4, btn / 4, btn / 4, btn / 4);
        pressable(sheetArrow);
        sheetArrow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchSheetPage(); }
        });
        bar.addView(sheetArrow, new LinearLayout.LayoutParams(btn, btn));

        sheetPanel.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sheetLayer.addView(sheetPanel, panelLp);
        root.addView(sheetLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View makeDot(boolean on) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(on ? 0xFFE8EDF2 : 0xFF3A4350);
        v.setBackground(d);
        return v;
    }

    /** 翻到第 index 页（0/1）。轨道靠 translationX 滑，所以拖动和点圆点共用同一条路径 */
    private void goSheetPage(int index, boolean animate) {
        sheetPageIndex = index;
        if (sheetTrack == null) return;
        int w = sheetPager != null ? sheetPager.getWidth() : 0;
        if (w <= 0) w = px(1f);
        float target = -index * w;
        sheetTrack.animate().cancel();
        if (animate) {
            sheetTrack.animate().translationX(target).setDuration(200).start();
        } else {
            sheetTrack.setTranslationX(target);
        }
        sheetDot1.setBackground(circle(index == 0 ? 0xFFE8EDF2 : 0xFF3A4350));
        sheetDot2.setBackground(circle(index == 1 ? 0xFFE8EDF2 : 0xFF3A4350));
        sheetArrow.setRotation(index == 0 ? 0f : 180f);
        sheetArrow.setContentDescription(index == 0 ? "下一页" : "上一页");
    }

    private void switchSheetPage() {
        goSheetPage(1 - sheetPageIndex, true);
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private View buildSheetPage(String[][] items) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int perRow = 5;
        for (int start = 0; start < items.length; start += perRow) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < perRow; i++) {
                int idx = start + i;
                if (idx < items.length) {
                    row.addView(makeSheetItem(items[idx][0], Integer.parseInt(items[idx][1]), items[idx][2]),
                            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            col.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return col;
    }

    private View makeSheetItem(String label, int glyph, final String action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, px(0.009f), 0, px(0.009f));
        item.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new GlyphIcon(glyph, 0xFFE8EDF2));
        int s = px(0.027f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        ip.bottomMargin = px(0.008f);
        item.addView(icon, ip);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFB6C0CC);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.0125f));
        tv.setGravity(Gravity.CENTER);
        item.addView(tv);

        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSheet();
                runSheetAction(action);
            }
        });
        return item;
    }

    private long sheetTransitionId;

    private void showSheet() {
        if (sheetLayer == null) return;
        sheetTransitionId++;
        sheetLayer.animate().cancel();
        sheetScrim.animate().cancel();
        sheetPanel.animate().cancel();
        sheetLayer.setTranslationY(0f);
        sheetLayer.setVisibility(View.VISIBLE);
        sheetLayer.bringToFront();
        if (floatBack != null) floatBack.setVisibility(View.GONE);
        sheetScrim.setAlpha(0f);
        sheetPanel.setTranslationY(px(0.24f));
        sheetScrim.animate().alpha(1f).setDuration(140).start();
        sheetPanel.animate().translationY(0f).setDuration(210)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        if (status != null && status.getVisibility() == View.VISIBLE) status.bringToFront();
        sheetPager.post(new Runnable() {
            @Override public void run() { goSheetPage(0, false); }
        });
    }

    private void hideSheet() {
        if (sheetLayer == null || sheetLayer.getVisibility() != View.VISIBLE) return;
        final long transition = ++sheetTransitionId;
        sheetScrim.animate().cancel();
        sheetPanel.animate().cancel();
        sheetScrim.animate().alpha(0f).setDuration(140).start();
        sheetPanel.animate().translationY(px(0.24f)).setDuration(160)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (transition != sheetTransitionId) return;
                        sheetLayer.setVisibility(View.GONE);
                        sheetPanel.setTranslationY(0f);
                        if (status != null && status.getVisibility() == View.VISIBLE) status.bringToFront();
                        if (floatBackEnabled) wakeFloatBack();
                    }
                }).start();
    }

    private void runSheetAction(String action) {
        BrowserWebView w = activeTab();
        if ("newtab".equals(action)) {
            openTab(HOME_URL);
        } else if ("tabs".equals(action)) {
            showTabsWithThumb(260);
        } else if ("bookmarks".equals(action)) {
            showBookmarks();
        } else if ("history".equals(action)) {
            showHistory();
        } else if ("download".equals(action)) {
            showDownloads();
        } else if ("share".equals(action)) {
            shareCurrent();
        } else if ("desktop".equals(action)) {
            if (w != null) {
                w.setDesktopUa(!w.isDesktopUa());
                showStatus(w.isDesktopUa() ? "桌面版网页" : "手机版网页", 1400);
            }
        } else if ("night".equals(action)) {
            toggleNightMode();
        } else if ("noimage".equals(action)) {
            toggleNoImage();
        } else if ("find".equals(action)) {
            showFindBar();
        } else if ("reader".equals(action)) {
            toggleReader();
        } else if ("adblock".equals(action)) {
            AdBlock.setEnabled(!AdBlock.isEnabled());
            saveUiState();
            showStatus(AdBlock.isEnabled() ? "广告过滤已开启" : "广告过滤已关闭", 1500);
            if (w != null && !w.isHomePage()) w.reload();
        } else if ("settings".equals(action)) {
            showSettings();
        } else if ("fullscreen".equals(action)) {
            setFullscreen(!toolbarHidden);
        } else if ("clear".equals(action)) {
            if (w != null) {
                long before = cacheSize();
                w.clearCache(true);
                showStatus(before > 0 ? "缓存已清除（约 " + mb(before) + "）" : "缓存已清除", 1600);
            }
        } else if ("cleardata".equals(action)) {
            confirmClearBrowsingData();
        } else if ("updates".equals(action)) {
            showAbout();
            checkForUpdate(true);
        } else if ("about".equals(action)) {
            showAbout();
        } else if ("closetab".equals(action)) {
            if (!closeCurrentTab()) showStatus("主页标签不能关闭", 1500);
        } else if ("exit".equals(action)) {
            askConfirm("退出车览浏览器", "确定退出吗？", "退出", new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    }

    /* ------------------------------------------------------ 页内查找 -- */

    private LinearLayout findBar;
    private EditText findInput;
    private TextView findCountText;
    private int findTotal = 0;

    private void buildFindBar() {
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(0xF2151A21);
        int pad = px(0.01f);
        findBar.setPadding(pad, 0, pad, 0);
        findBar.setVisibility(View.GONE);

        findInput = new EditText(this);
        findInput.setSingleLine(true);
        findInput.setHint("在页面中查找");
        findInput.setHintTextColor(0xFF7A8794);
        findInput.setTextColor(0xFFE8EDF2);
        findInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(36f / 2880f));
        findInput.setInputType(InputType.TYPE_CLASS_TEXT);
        findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        findInput.setBackgroundColor(0x00000000);
        findInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                doFind(s == null ? "" : s.toString());
            }
        });
        findInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (id == EditorInfo.IME_ACTION_SEARCH
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    doFind(findInput.getText().toString());
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        findBar.addView(findInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        findCountText = new TextView(this);
        findCountText.setTextColor(0xFF9AA6B4);
        findCountText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        findCountText.setPadding(px(0.008f), 0, px(0.01f), 0);
        findBar.addView(findCountText);

        int btn = px(0.04f);
        ImageView prev = glyphButton(GlyphIcon.FORWARD, -90f, new View.OnClickListener() {
            @Override public void onClick(View v) { findStep(false); }
        });
        findBar.addView(prev, new LinearLayout.LayoutParams(btn, btn));

        ImageView next = glyphButton(GlyphIcon.FORWARD, 90f, new View.OnClickListener() {
            @Override public void onClick(View v) { findStep(true); }
        });
        findBar.addView(next, new LinearLayout.LayoutParams(btn, btn));

        ImageView close = glyphButton(GlyphIcon.CLOSE, 0f, new View.OnClickListener() {
            @Override public void onClick(View v) { hideFindBar(); }
        });
        findBar.addView(close, new LinearLayout.LayoutParams(btn, btn));
    }

    private ImageView glyphButton(int glyph, float rotation, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new GlyphIcon(glyph, 0xFFE8EDF2));
        v.setRotation(rotation);
        v.setPadding(px(0.008f), px(0.008f), px(0.008f), px(0.008f));
        pressable(v);
        v.setOnClickListener(l);
        return v;
    }

    private void showFindBar() {
        BrowserWebView w = activeTab();
        if (w == null || w.isHomePage()) {
            showStatus("先在网页里再查找", 1400);
            return;
        }
        findBar.setVisibility(View.VISIBLE);
        findInput.requestFocus();
        findInput.selectAll();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(findInput, 0);
        } catch (Throwable ignored) {
        }
    }

    private void hideFindBar() {
        if (findBar == null) return;
        findBar.setVisibility(View.GONE);
        hideKeyboard();
        BrowserWebView w = activeTab();
        if (w != null) w.clearMatches();
        findCountText.setText("");
        findTotal = 0;
    }

    private void doFind(String q) {
        BrowserWebView w = activeTab();
        if (w == null) return;
        if (q == null || q.trim().isEmpty()) {
            w.clearMatches();
            findCountText.setText("");
            findTotal = 0;
            return;
        }
        w.findAllAsync(q);
    }

    private void findStep(boolean forward) {
        BrowserWebView w = activeTab();
        if (w == null || findTotal == 0) return;
        w.findNext(forward);
    }

    /* ------------------------------------------------------ 阅读模式 -- */

    private String assetText(String name) {
        java.io.InputStream in = null;
        try {
            in = getAssets().open(name);
            byte[] buf = new byte[(int) in.available()];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private void toggleReader() {
        BrowserWebView w = activeTab();
        if (w == null || w.isHomePage()) {
            showStatus("先打开一篇网页再进阅读模式", 1500);
            return;
        }
        String js = assetText("reader.js");
        if (js.isEmpty()) {
            showStatus("阅读模式脚本缺失", 1500);
            return;
        }
        w.evaluateJavascript(js, null);
    }

    /* -------------------------------------------------- 长按链接菜单 -- */

    @Override
    public void onLinkLongPress(BrowserWebView web, String url) {
        // 首页卡片自带长按（进编辑模式），别在上面再叠一层链接菜单
        String cur = web.getUrl();
        if (cur != null && cur.startsWith("file:///android_asset/")) return;
        showLinkMenu(url);
    }

    private void showLinkMenu(final String url) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(0.014f);
        box.setPadding(pad, pad, pad, pad / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1F26);
        bg.setCornerRadius(px(0.02f));
        bg.setStroke(Math.max(2, px(2f / 2880f)), 0xFF33383F);
        box.setBackground(bg);

        // 显示时把 %E7%99%BE%E5%BA%A6 这类百分号编码解回中文，长链接才好认
        String shown = url;
        try {
            shown = Uri.decode(url);
        } catch (Throwable ignored) {
        }
        TextView t = new TextView(this);
        t.setText(shown);
        t.setTextColor(0xFFAAB7C5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32f / 2880f));
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setPadding(px(0.008f), px(0.004f), px(0.008f), px(0.012f));
        box.addView(t);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();

        box.addView(linkMenuRow("在新标签页打开", 0xFFE6E9F0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                openTab(url);
            }
        }));
        box.addView(linkMenuRow("复制链接", 0xFF6FA8FF, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                copyLink(url);
            }
        }));
        box.addView(linkMenuRow("取消", 0xFF8C98A6, new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        }));

        dlg.show();
        styleDialog(dlg, 1200f);
    }

    private View linkMenuRow(String label, int color, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38f / 2880f));
        b.setPadding(px(0.008f), px(0.014f), px(0.008f), px(0.014f));
        pressable(b);
        b.setOnClickListener(l);
        return b;
    }

    /* ------------------------------------------------------ 整页容器 -- */
    /*
     * Via 的历史 / 书签 / 下载 / 设置都是「整页」而不是弹窗：
     * 顶部一条返回栏 + 可滚动列表。弹窗在车机上一是字小，二是长列表没法滚。
     * 这里做一个通用容器，各页面只管往里塞行。
     */

    private FrameLayout pageLayer;

    private void buildPageLayer() {
        pageLayer = new FrameLayout(this);
        pageLayer.setVisibility(View.GONE);
        pageLayer.setBackgroundColor(0xFF0D1117);
        root.addView(pageLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean pageVisible() {
        return pageLayer != null && pageLayer.getVisibility() == View.VISIBLE;
    }

    /** Keep a stable page key when list titles include changing counts. */
    private String pageRoute(String title) {
        int split = title.indexOf(" · ");
        return split > 0 ? title.substring(0, split) : title;
    }

    private LinearLayout openPage(String title, String action, View.OnClickListener onAction) {
        if (pageLayer == null) return new LinearLayout(this);
        // openPage() 会把这些引用换掉；非关于页时 refreshUpdateCard() 直接返回，不碰已分离的 View
        updateStatusView = null;
        updateDetailView = null;
        String route = pageRoute(title);
        int restoreScrollY = 0;
        boolean replace = pageVisible() && route.equals(currentPageRoute);
        if (pageVisible() && pageLayer.getChildCount() > 0) {
            View old = pageLayer.getChildAt(0);
            if (old instanceof ViewGroup && ((ViewGroup) old).getChildCount() > 1) {
                restoreScrollY = ((ViewGroup) old).getChildAt(1).getScrollY();
            }
            if (!replace) {
                if (!pageParents.isEmpty() && route.equals(pageParents.peek().route)) {
                    restoreScrollY = pageParents.pop().scrollY;
                } else {
                    pageParents.push(new PageFrame(old, currentPageRoute, restoreScrollY));
                }
            }
        }
        currentPageScrollY = restoreScrollY;
        currentPageRoute = route;
        pageTransitionId++;
        pageLayer.animate().cancel();
        pageLayer.removeAllViews();
        pageLayer.setVisibility(View.VISIBLE);
        pageLayer.setAlpha(0f);

        final int barH = px(132f / 2880f);
        final int btnW = px(120f / 2880f);
        final int pad = px(0.075f);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0D1117);
        pageLayer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF12161C);
        bar.setPadding(pad, 0, pad, 0);
        ImageView back = makeBtn(GlyphIcon.BACK,
                Math.max(0, (btnW - px(64f / 2880f)) / 2), "返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePage(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(btnW, barH));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE8EDF2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(44f / 2880f));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(px(0.008f), 0, px(0.008f), 0);
        bar.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        if (action != null && onAction != null) {
            TextView a = new TextView(this);
            a.setText(action);
            a.setTextColor(0xFF8EC5FF);
            a.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(34f / 2880f));
            a.setGravity(Gravity.CENTER);
            a.setContentDescription(action);
            a.setPadding(px(0.012f), 0, px(0.012f), 0);
            pressable(a);
            a.setOnClickListener(onAction);
            bar.addView(a, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, px(0.012f), pad, px(0.05f));
        sv.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final int restoreY = restoreScrollY;
        if (restoreY > 0) sv.post(new Runnable() {
            @Override public void run() { sv.scrollTo(0, restoreY); }
        });
        pageLayer.animate().alpha(1f).setDuration(150).start();
        return body;
    }

    private void closePage() {
        if (pageLayer == null || pageLayer.getVisibility() != View.VISIBLE) return;
        final long transition = ++pageTransitionId;
        pageLayer.animate().cancel();
        pageLayer.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() {
                if (transition != pageTransitionId) return;
                if (!pageParents.isEmpty()) {
                    PageFrame parent = pageParents.pop();
                    pageLayer.removeAllViews();
                    pageLayer.addView(parent.view, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    currentPageRoute = parent.route;
                    pageLayer.setAlpha(0f);
                    pageLayer.animate().alpha(1f).setDuration(120).start();
                    parent.view.post(new Runnable() {
                        @Override public void run() {
                            if (parent.view instanceof ViewGroup
                                    && ((ViewGroup) parent.view).getChildCount() > 1) {
                                ((ViewGroup) parent.view).getChildAt(1).scrollTo(0, parent.scrollY);
                            }
                        }
                    });
                } else {
                    currentPageRoute = "";
                    pageLayer.setVisibility(View.GONE);
                    pageLayer.removeAllViews();
                }
            }
        }).start();
    }

    /** 按下时轻微缩放，避免整块降到 55% 造成生硬闪烁。 */
    private void pressable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    view.animate().cancel();
                    view.animate().scaleX(0.975f).scaleY(0.975f).alpha(0.82f).setDuration(70).start();
                } else if (a == android.view.MotionEvent.ACTION_UP
                        || a == android.view.MotionEvent.ACTION_CANCEL) {
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start();
                }
                return false;
            }
        });
    }

    private View pageRow(String title, String sub, String right, boolean danger,
                         View.OnClickListener click, View.OnClickListener rightClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(0.009f), 0, px(0.009f));
        if (click != null) {
            pressable(row);
            row.setOnClickListener(click);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE6E9F0);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38f / 2880f));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(0xFF98A5B3);
            s.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s.setPadding(0, px(0.004f), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) {
            TextView r = new TextView(this);
            r.setText(right);
            r.setTextColor(danger ? 0xFFE07070 : 0xFF8EC5FF);
            r.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32f / 2880f));
            r.setGravity(Gravity.CENTER);
            r.setMinimumWidth(px(0.08f));
            r.setMinHeight(px(0.036f));
            r.setPadding(px(0.012f), px(0.006f), px(0.004f), px(0.006f));
            r.setContentDescription(right);
            if (rightClick != null) {
                pressable(r);
                r.setOnClickListener(rightClick);
            }
            row.addView(r);
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(0xFF1B2129);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1f / 2880f))));
        return wrap;
    }

    private View pageLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF8A98A8);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        t.setPadding(px(0.01f), px(0.014f), 0, px(0.005f));
        return t;
    }

    private View pageEmpty(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(px(0.03f), px(0.08f), px(0.03f), px(0.03f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFB6C0CC);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(40f / 2880f));
        t.setGravity(Gravity.CENTER);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0xFF98A5B3);
        s.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, px(0.012f), 0, 0);
        box.addView(t);
        box.addView(s);
        return box;
    }

    /* ---------------------------------------------------------- 书签页 -- */

    private void showBookmarks() {
        final java.util.List<String> fav = store.favorites();
        LinearLayout body = openPage("书签 · " + fav.size(), null, null);
        if (fav.isEmpty()) {
            body.addView(pageEmpty("还没有书签", "在网页上点工具条的星标就能收藏"));
            return;
        }
        for (final String url : fav) {
            String name = displayName(url);
            body.addView(pageRow(name, url, "✕", true,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            closePage();
                            openFromAddressBar(url);
                        }
                    },
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            store.removeFavorite(url);
                            pushHomeData();
                            showBookmarks();
                        }
                    }));
        }
    }

    /** 常见站点给个中文名；用户改过名就用用户的名字 */
    private static final String[][] KNOWN_SITES = {
            {"baidu.com", "百度"},
            {"google.com", "Google"},
            {"youtube.com", "YouTube"},
            {"bilibili.com", "哔哩哔哩"},
            {"zhihu.com", "知乎"},
            {"taobao.com", "淘宝"},
            {"weibo.com", "微博"},
            {"douban.com", "豆瓣"},
            {"jd.com", "京东"},
            {"youku.com", "优酷"},
            {"iqiyi.com", "爱奇艺"},
            {"qq.com", "腾讯视频"},
            {"music.163.com", "网易云音乐"}
    };

    private String displayName(String url) {
        String custom = store.titleOf(url);
        if (custom != null && !custom.isEmpty()) return custom;
        String host = SiteStore.hostOf(url);
        for (String[] s : KNOWN_SITES) {
            if (host.endsWith(s[0])) return s[1];
        }
        return host.isEmpty() ? url : host;
    }

    /* ---------------------------------------------------------- 设置页 -- */

    private static final String[] ENGINE_IDS = {"baidu", "bing", "sogou", "google"};
    private static final String[] ENGINE_NAMES = {"百度", "必应", "搜狗", "谷歌"};

    private String engineName(String id) {
        for (int i = 0; i < ENGINE_IDS.length; i++) {
            if (ENGINE_IDS[i].equals(id)) return ENGINE_NAMES[i];
        }
        return ENGINE_NAMES[0];
    }

    private TextView settingChoice(String label, boolean selected,
                                  View.OnClickListener click) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
        chip.setTextColor(selected ? 0xFFC6E6CC : 0xFFAFBAC5);
        chip.setContentDescription("返回按钮隐藏 " + label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF26372B : 0xFF171C22);
        bg.setCornerRadius(px(0.006f));
        bg.setStroke(Math.max(1, px(1f / 2880f)), selected ? 0xFF4B7654 : 0xFF29313A);
        chip.setBackground(bg);
        pressable(chip);
        chip.setOnClickListener(click);
        return chip;
    }

    private View pageToggleRow(String title, String subtitle, boolean checked,
                               android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(0.008f), 0, px(0.008f));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(0xFFE6E9F0);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(37f / 2880f));
        text.addView(label);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = new TextView(this);
            detail.setText(subtitle);
            detail.setTextColor(0xFF98A5B3);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30f / 2880f));
            detail.setPadding(0, px(0.003f), 0, 0);
            text.addView(detail);
        }
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setMinWidth(px(0.075f));
        toggle.setMinimumWidth(px(0.075f));
        toggle.setMinimumHeight(px(0.045f));
        toggle.setContentDescription(title);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        toggle.setSwitchMinWidth(px(0.04f));
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(0.05f)));
        View divider = new View(this);
        divider.setBackgroundColor(0xFF1B2129);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1f / 2880f))));
        return wrap;
    }

    private void showSettings() {
        BrowserWebView active = activeTab();
        boolean desktop = active != null && active.isDesktopUa();
        LinearLayout body = openPage("设置", null, null);

        body.addView(pageLabel("浏览"));
        body.addView(pageRow("搜索引擎", engineName(store.engine()), "›", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showEnginePage(); }
                }, null));
        body.addView(pageRow("网页版本", desktop ? "桌面版" : "手机版", "切换", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        BrowserWebView cur = activeTab();
                        if (cur != null) cur.setDesktopUa(!cur.isDesktopUa());
                        showSettings();
                    }
                }, null));

        body.addView(pageLabel("显示"));
        body.addView(pageToggleRow("夜间模式", "对网页套用深色配色", nightMode,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        if (nightMode != checked) { toggleNightMode(); showSettings(); }
                    }
                }));
        body.addView(pageToggleRow("无图模式", "减少网页图片流量", noImageMode,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        if (noImageMode != checked) { toggleNoImage(); showSettings(); }
                    }
                }));
        body.addView(pageToggleRow("广告过滤", AdBlock.blockedCount() + " 个请求已拦截",
                AdBlock.isEnabled(), new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        if (AdBlock.isEnabled() != checked) {
                            AdBlock.setEnabled(checked);
                            saveUiState();
                            BrowserWebView cur = activeTab();
                            if (cur != null && !cur.isHomePage()) cur.reload();
                            showSettings();
                        }
                    }
                }));

        body.addView(pageLabel("车机触控"));
        body.addView(pageToggleRow("应用内返回", "触摸屏幕后出现，空闲时自动隐藏",
                floatBackEnabled, new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                        floatBackEnabled = checked;
                        saveUiState();
                        if (!checked) {
                            uiHandler.removeCallbacks(hideFloatBackTask);
                            if (floatBack != null) {
                                floatBack.animate().cancel();
                                floatBack.setVisibility(View.GONE);
                            }
                        }
                        showSettings();
                    }
                }));
        body.addView(pageRow("自动隐藏时间", floatBackSeconds + " 秒", null, false, null, null));
        LinearLayout durations = new LinearLayout(this);
        durations.setOrientation(LinearLayout.HORIZONTAL);
        durations.setGravity(Gravity.CENTER_VERTICAL);
        int[] seconds = {4, 6, 8};
        for (final int value : seconds) {
            TextView chip = settingChoice(value + " 秒", value == floatBackSeconds,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            floatBackSeconds = value;
                            saveUiState();
                            if (floatBack != null && floatBack.getVisibility() == View.VISIBLE) wakeFloatBack();
                            showSettings();
                        }
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(0.046f), 1f);
            lp.rightMargin = px(0.008f);
            durations.addView(chip, lp);
        }
        body.addView(durations);

        body.addView(pageRow("下载目录",
                DownloadCenter.get(this).destinationLabel(this), "更改", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showDownloadDestination(); }
                }, null));
        body.addView(pageLabel("数据"));
        body.addView(pageRow("清除缓存", mb(cacheSize()), "清除", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        BrowserWebView cur = activeTab();
                        if (cur != null) cur.clearCache(true);
                        showStatus("缓存已清除", 1500);
                        showSettings();
                    }
                }, null));
        body.addView(pageRow("清除浏览数据", "历史 · Cookie · 缓存", "清除", true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmClearBrowsingData(); }
                }, null));

        body.addView(pageLabel("更新与关于"));
        body.addView(pageRow("检查更新", "GitHub Release · 自动下载并校验", "检查", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showAbout(); checkForUpdate(true); }
                }, null));
        body.addView(pageRow("关于车览浏览器", "版本 " + BuildConfig.VERSION_NAME, "›", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showAbout(); }
                }, null));
    }

    private void showEnginePage() {
        LinearLayout body = openPage("搜索引擎", null, null);
        final String cur = store.engine();
        for (int i = 0; i < ENGINE_IDS.length; i++) {
            final String id = ENGINE_IDS[i];
            final String name = ENGINE_NAMES[i];
            body.addView(pageRow(name, i == 0 ? "默认" : null, id.equals(cur) ? "✓" : "", false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            store.setEngine(id);
                            pushHomeData();
                            showStatus("搜索引擎：" + name, 1500);
                            showSettings();
                        }
                    }, null));
        }
    }

    private void confirmClearBrowsingData() {
        askConfirm("清除浏览数据", "会删掉全部历史记录、Cookie 与缓存，书签保留。", "清除",
                new Runnable() {
                    @Override public void run() {
                        store.clearVisits();
                        CookieManager cm = CookieManager.getInstance();
                        cm.removeAllCookies(null);
                        cm.flush();
                        for (BrowserWebView b : tabs) {
                            b.clearCache(true);
                            b.clearHistory();
                        }
                        pushHomeData();
                        showStatus("浏览数据已清除", 1600);
                        showSettings();
                    }
                });
    }

    private void toggleNoImage() {
        noImageMode = !noImageMode;
        saveUiState();
        for (BrowserWebView b : tabs) {
            try {
                b.getSettings().setBlockNetworkImage(noImageMode);
                if (!b.isHomePage()) b.reload();
            } catch (Throwable ignored) {
            }
        }
        showStatus(noImageMode ? "无图模式：开（不再加载图片）" : "无图模式：关", 1800);
    }

    /* ------------------------------------------------------ 确认小弹窗 -- */

    private void askConfirm(String title, String msg, String okText, final Runnable onOk) {
        int pad = px(56f / 2880f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, px(20f / 2880f));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1F26);
        bg.setCornerRadius(px(30f / 2880f));
        bg.setStroke(Math.max(2, px(2f / 2880f)), 0xFF33383F);
        box.setBackground(bg);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFF2F4F8);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(44f / 2880f));
        box.addView(t);

        TextView m = new TextView(this);
        m.setText(msg);
        m.setTextColor(0xFFB6C0CC);
        m.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(34f / 2880f));
        m.setLineSpacing(px(10f / 2880f), 1f);
        m.setPadding(0, px(18f / 2880f), 0, px(26f / 2880f));
        box.addView(m);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextColor(0xFF9AA6B4);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38f / 2880f));
        cancel.setPadding(px(0.02f), px(0.008f), px(0.02f), px(0.008f));
        pressable(cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancel);

        TextView ok = new TextView(this);
        ok.setText(okText);
        ok.setTextColor(0xFFE07070);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38f / 2880f));
        ok.setPadding(px(0.03f), px(0.008f), px(0.01f), px(0.008f));
        pressable(ok);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                onOk.run();
            }
        });
        btns.addView(ok);
        box.addView(btns);

        dlg.show();
        styleDialog(dlg, 1320f);
    }

    private void styleDialog(AlertDialog dlg, float widthPx) {
        android.view.Window win = dlg.getWindow();
        if (win == null) return;
        win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        WindowManager.LayoutParams wlp = win.getAttributes();
        wlp.width = px(widthPx / 2880f);
        wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        win.setAttributes(wlp);
    }
    /** 全屏 = 只隐藏我们自己的工具条（网页照常可用），并且必须留一个触屏能点的恢复入口 */
    private void setFullscreen(boolean on) {
        toolbarHidden = on;
        toolbar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (toolbarHandle != null) {
            toolbarHandle.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        applyImmersive();
        showStatus(on ? "已进入全屏 · 点右上角按钮恢复" : "工具条已显示", 1800);
    }

    private long cacheSize() {
        long total = dirSize(getCacheDir());
        File parent = getFilesDir() == null ? null : getFilesDir().getParentFile();
        if (parent != null) total += dirSize(new File(parent, "app_webview"));
        return total;
    }

    private long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        long n = 0;
        for (File k : kids) n += dirSize(k);
        return n;
    }

    private String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void showHistory() {
        final java.util.List<SiteStore.Visit> list = store.visits();
        LinearLayout body = openPage("历史记录 · " + list.size(),
                list.isEmpty() ? null : "清空", new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmClearHistory(); }
                });
        if (list.isEmpty()) {
            body.addView(pageEmpty("还没有历史记录", "访问过的页面会按时间倒序排在这里"));
            return;
        }
        String lastKey = null;
        for (final SiteStore.Visit v : list) {
            String key = dayKey(v.time);
            if (!key.equals(lastKey)) {
                lastKey = key;
                body.addView(pageLabel(dayLabel(v.time)));
            }
            String title = (v.title == null || v.title.isEmpty() || v.title.startsWith("http"))
                    ? displayName(v.url) : v.title;
            body.addView(pageRow(title, timeLabel(v.time) + "  ·  " + v.url, "✕", true,
                    new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            closePage();
                            openFromAddressBar(v.url);
                        }
                    },
                    new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            store.deleteVisit(v.url);
                            showHistory();
                        }
                    }));
        }
    }

    private void confirmClearHistory() {
        askConfirm("清空历史记录", "会删掉全部 " + store.visits().size() + " 条记录，书签不受影响。", "清空",
                new Runnable() {
                    @Override public void run() {
                        store.clearVisits();
                        pushHomeData();
                        showStatus("历史记录已清空", 1500);
                        showHistory();
                    }
                });
    }

    private String dayKey(long ts) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return f.format(new java.util.Date(ts <= 0 ? System.currentTimeMillis() : ts));
    }

    private String dayLabel(long ts) {
        long now = System.currentTimeMillis();
        String k = dayKey(ts);
        if (k.equals(dayKey(now))) return "今天";
        if (k.equals(dayKey(now - 86400000L))) return "昨天";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA);
        return f.format(new java.util.Date(ts <= 0 ? now : ts));
    }

    private String timeLabel(long ts) {
        if (ts <= 0) return "";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(ts));
    }

    private void shareCurrent() {
        BrowserWebView w = activeTab();
        String url = (w == null) ? null : w.getUrl();
        if (url == null || url.startsWith(HOME_URL)) {
            showStatus("先打开一个网页再分享", 1600);
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        String title = w.getTitle();
        send.putExtra(Intent.EXTRA_SUBJECT, title == null ? url : title);
        send.putExtra(Intent.EXTRA_TEXT, url);
        if (send.resolveActivity(getPackageManager()) == null) {
            copyLink(url);
            return;
        }
        try {
            startActivity(Intent.createChooser(send, "分享到"));
        } catch (Throwable t) {
            copyLink(url);
        }
    }

    /** 车机上常常没有可分享的 App，退化成「复制链接」也算完成用户的目的 */
    private void copyLink(String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("链接", url));
                showStatus("已复制链接：" + url, 2000);
                return;
            }
        } catch (Throwable ignored) {
        }
        showStatus("没有可分享的应用", 1600);

    }
    /** 夜间模式：直接把网页反色。setForceDark 在车机深色主题下实测无效，改走 JS 注入。 */
    private static final String NIGHT_JS =
            "(function(){var d=document.documentElement;if(!d)return;"
                    + "if(!document.getElementById('__kb_night')){var s=document.createElement('style');"
                    + "s.id='__kb_night';s.textContent="
                    + "'html{filter:invert(1) hue-rotate(180deg)!important;background:#0b0e12!important}'+"
                    + "'img,video,picture,canvas,svg{filter:invert(1) hue-rotate(180deg)!important}';"
                    + "d.appendChild(s);}d.style.colorScheme='dark';})()";
    private static final String DAY_JS =
            "(function(){var s=document.getElementById('__kb_night');"
                    + "if(s&&s.parentNode)s.parentNode.removeChild(s);"
                    + "var d=document.documentElement;if(d)d.style.colorScheme='';})()";

    private void toggleNightMode() {
        nightMode = !nightMode;
        saveUiState();
        for (BrowserWebView b : tabs) applyNightMode(b);
        showStatus(nightMode ? "夜间模式：开" : "夜间模式：关", 1600);
    }

    private void applyNightMode(BrowserWebView web) {
        // 自家首屏本来就是深色，别再反成白底
        if (web == null || web.isHomePage()) return;
        try {
            web.evaluateJavascript(nightMode ? NIGHT_JS : DAY_JS, null);
        } catch (Throwable ignored) {
        }
    }
    /* ------------------------------------------------------------ 首屏 -- */

    private void openHome() {
        BrowserWebView web = createTabView();
        homeView = web;
        tabs.addLast(web);
        activeWeb = web;
        web.setVisibility(View.VISIBLE);
        web.loadUrl(HOME_URL);
        web.requestFocus();
    }

    /** 把收藏 / 最近打开 / 搜索引擎推给首屏（每个停在首屏的标签页都要推，否则新开的标签是空的） */
    void pushHomeData() {
        String json = store.toJson();
        String js = "window.tvHome && window.tvHome.setData(" + JSONObject.quote(json) + ")";
        boolean any = false;
        for (BrowserWebView w : tabs) {
            if (w != null && w.isHomePage()) {
                w.evaluateJavascript(js, null);
                any = true;
            }
        }
        if (!any && homeView != null && homeView.isHomePage()) {
            homeView.evaluateJavascript(js, null);
        }
    }

    /* ------------------------------------------------------------ 标签页 -- */

    BrowserWebView activeTab() {
        if (activeWeb != null && tabs.contains(activeWeb)) return activeWeb;
        return tabs.peekLast();
    }

    public boolean isActiveTab(BrowserWebView web) {
        return web != null && activeTab() == web;
    }

    private BrowserWebView createTabView() {
        BrowserWebView web = new BrowserWebView(this);
        BrowserBridge bridge = new BrowserBridge(this, web);
        web.addJavascriptInterface(bridge, "kbHost");
        web.setBridge(bridge);
        web.setListener(this);
        web.configure();
        // 无图模式下新开的标签也要跟着省流量
        web.getSettings().setBlockNetworkImage(noImageMode);
        web.setFindListener(new WebView.FindListener() {
            @Override
            public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches,
                                             boolean isDoneCounting) {
                if (findCountText == null) return;
                findTotal = numberOfMatches;
                if (numberOfMatches == 0) {
                    findCountText.setText("无结果");
                } else {
                    // activeMatchOrdinal 是从 0 开始的，显示成 1/2 更符合直觉
                    findCountText.setText((activeMatchOrdinal + 1) + "/" + numberOfMatches);
                }
            }
        });
        host.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setVisibility(View.GONE);
        return web;
    }

    /** 把当前标签页藏到下面（不销毁），把新的这一页摆上来 */
    private void stackOn(BrowserWebView web) {
        BrowserWebView cur = activeTab();
        if (cur != null && cur != web) {
            cur.onPause();
            cur.setVisibility(View.GONE);
        }
        // 超过上限就回收最老的 —— 但首屏永远不回收，否则用户再也回不到搜索框
        while (tabs.size() >= MAX_TABS) {
            BrowserWebView victim = null;
            for (BrowserWebView cand : tabs) {
                if (cand != web && cand != cur && cand != homeView) {
                    victim = cand;      // ArrayDeque 是插入序，第一个命中的就是最老的
                    break;
                }
            }
            if (victim == null) break;
            destroyTab(victim);
        }
        tabs.addLast(web);
        activeWeb = web;
        web.resetBackPress();          // 新标签页重新开始算"连按两下返回"
        web.setVisibility(View.VISIBLE);
        web.onResume();
        web.requestFocus();
    }

    /** 打开一个新标签页并加载 url */
    private void openTab(String url) {
        BrowserWebView web = createTabView();
        stackOn(web);
        web.loadUrl(url);
    }

    /** 网页请求开新标签页（_blank / window.open 走这条路） */
    public void openTabFromWeb(BrowserWebView source, String url) {
        if (!isActiveTab(source) || url == null) return;
        if (!SiteStore.isHttpUrl(url)) return;
        openTab(url);
        Log.i(TAG, "新标签页: " + url);
    }

    /**
     * 关掉当前标签页（原来那一页原地还原，不会重新加载）。
     * 首屏不能关：它是标签页栈最底下那一层，关了用户就回不到搜索框了。
     */
    private boolean closeCurrentTab() {
        if (tabs.size() <= 1) return false;
        BrowserWebView cur = activeTab();
        if (cur == null || cur == homeView) return false;
        destroyTab(cur);
        BrowserWebView next = activeTab();
        if (next != null && next.isHomePage()) pushHomeData();
        return true;
    }

    private BrowserWebView newWindowTab() {
        BrowserWebView web = createTabView();
        stackOn(web);
        return web;
    }

    /* ------------------------------------------------------- 测试用的入口 -- */

    /** 当前有几个标签页 */
    int tabCount() {
        return tabs.size();
    }

    /** 按下标取标签页（0 = 首屏） */
    BrowserWebView tabAt(int index) {
        int i = 0;
        for (BrowserWebView w : tabs) {
            if (i++ == index) return w;
        }
        return null;
    }

    /** 首屏那一个 */
    BrowserWebView homeTab() {
        return homeView;
    }

    SiteStore siteStore() {
        return store;
    }

    /* ------------------------------------------------------------ 首屏操作 -- */

    void favoriteFromHome(String url) {
        if (store.isFavorite(url)) {
            showStatus(getString(R.string.favorited), 1500);
        } else if (store.addFavorite(url)) {
            showStatus(getString(R.string.favorited), 1800);
        } else {
            showStatus(getString(R.string.fav_limit, SiteStore.MAX_FAVORITES), 2500);
        }
        pushHomeData();
    }

    void unfavoriteFromHome(String url) {
        showStatus(getString(R.string.unfavorited), 1800);
        store.removeFavorite(url);
        pushHomeData();
    }

    void forgetFromHome(String url) {
        store.forget(url);
        showStatus(getString(R.string.removed), 1800);
        pushHomeData();
    }

    void setEngineFromHome(String id) {
        store.setEngine(id);
        pushHomeData();
    }

    /** 长按卡片「编辑」：改名字 / 改网址，位置不变 */
    void editFavoriteFromHome(final String url) {
        String canonical = SiteStore.canonical(url);
        int pad = px(0.016f);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);box.setPadding(pad,pad,pad,pad);
        GradientDrawable bg = new GradientDrawable();bg.setColor(0xFF18212B);bg.setCornerRadius(px(0.012f));box.setBackground(bg);
        TextView title = new TextView(this);title.setText("编辑站点");title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(42f/2880f));box.addView(title);
        box.addView(pageLabel("站点名称"));
        EditText nameIn = new EditText(this);nameIn.setSingleLine(true);nameIn.setText(displayName(url));nameIn.setContentDescription("站点名称");
        nameIn.setTextColor(0xFFE6E9F0);nameIn.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(38f/2880f));
        box.addView(nameIn,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,px(0.041f)));
        box.addView(pageLabel("网址"));
        EditText urlIn = new EditText(this);urlIn.setSingleLine(true);urlIn.setText(canonical);urlIn.setContentDescription("站点网址");
        urlIn.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        urlIn.setTextColor(0xFFE6E9F0);urlIn.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(36f/2880f));
        box.addView(urlIn,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,px(0.041f)));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box).create();
        LinearLayout actions = new LinearLayout(this);actions.setGravity(Gravity.END);actions.setPadding(0,pad,0,0);
        actions.addView(updateActionButton("取消",v -> dialog.dismiss()),new LinearLayout.LayoutParams(px(0.075f),px(0.044f)));
        actions.addView(updateActionButton("保存",v -> {
            String value = urlIn.getText().toString().trim();
            if(!value.contains("://")) value = "https://"+value;
            if(!SiteStore.isHttpUrl(value)) { urlIn.setError("请输入有效网址");urlIn.requestFocus();return; }
            store.rename(url,nameIn.getText().toString().trim(),value);pushHomeData();dialog.dismiss();showStatus("站点已保存",1600);
        }),new LinearLayout.LayoutParams(px(0.075f),px(0.044f)));
        box.addView(actions);dialog.show();styleDialog(dialog,1520f);
    }

    private void showEnginePicker() { showEnginePage(); }

    /** 首屏调完「移动位置」把收藏顺序交回来 —— 顺序已经生效在页面上了，不用再回推 */
    void saveFavoritesFromHome(String json) {
        store.replaceFavorites(json);
    }

    /* ------------------------------------------------------------- 生命周期 -- */

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        resumePendingUpdate();
        BrowserWebView web = activeTab();
        if (web != null) {
            web.onResume();
            web.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        BrowserWebView web = activeTab();
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    protected void onDestroy() {
        unregisterUpdateReceiver();
        uiHandler.removeCallbacksAndMessages(null);
        try {
            DownloadCenter.get(this).setListener(null);
        } catch (Throwable ignored) {
        }
        for (BrowserWebView web : tabs) {
            host.removeView(web);
            web.destroy();
        }
        tabs.clear();
        for (Bitmap b : tabThumbs.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        tabThumbs.clear();
        thumbStamps.clear();
        activeWeb = null;
        homeView = null;
        super.onDestroy();
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /* ---------------------------------------------------------------- 按键 -- */

    /**
     * 在 Activity 层统一截按键：这样不管 WebView 有没有焦点，遥控器都管用。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && sheetLayer != null && sheetLayer.getVisibility() == View.VISIBLE) {
            if (event.getAction() == KeyEvent.ACTION_UP) hideSheet();
            return true;
        }
        // 整页开着时，返回键先关页面（不然会去操作被盖住的网页）
        if (pageVisible() && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) closePage();
            return true;
        }
        // 地址栏正在输入时，按键交给 EditText；但回车必须由我们直接提交。
        // 车机的物理键盘 / 遥控器只会发 KEYCODE_ENTER，很多 WebView 机型上 IME 不会
        // 触发 onEditorAction，光靠 setOnEditorActionListener 会出现「字打进去了但没跳转」。
        if (addr != null && addr.hasFocus()) {
            boolean enter = event.getAction() == KeyEvent.ACTION_DOWN
                    && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER);
            if (enter) {
                String text = addr.getText().toString();
                Log.i(TAG, "地址栏回车: " + text);
                openFromAddressBar(text);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        BrowserWebView web = activeTab();
        if (web != null && web.handleRemoteKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /* ------------------------------------------------------------ Listener -- */

    @Override
    public void onBridgeReady(BrowserWebView web) {
        if (!isActiveTab(web)) return;
        hideStatus();
        if (web.isHomePage()) pushHomeData();
    }

    @Override
    public void onExitRequest(boolean longPress) {
        if (longPress) {
            // 长按返回 / 连按两下返回 = 关掉当前标签页；只剩首屏时没得关，才直接退出应用
            if (closeCurrentTab()) return;
            finish();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastExitAt < 2000) {
            finish();
            return;
        }
        lastExitAt = now;
        showStatus(getString(R.string.exit_hint), 2000);
    }

    @Override
    public void onProgress(BrowserWebView web, int progress) {
        if (!isActiveTab(web) || loadBar == null) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams lp = loadBar.getLayoutParams();
        lp.width = Math.max(0, (int) (screenW * (progress / 100f)));
        loadBar.setLayoutParams(lp);
        loadBar.setAlpha(1f);
        if (progress >= 100) {
            loadBar.animate().alpha(0f).setStartDelay(200).setDuration(220).start();
        }
    }

    @Override
    public void onPageLoading(BrowserWebView web, boolean loading) {
        if (!isActiveTab(web)) return;
        if (loading) {
            // 有时间上限：页面一直不 onPageFinished 也不会把加载提示糊在屏幕上
            showStatus(getString(R.string.loading), LOADING_TIMEOUT_MS);
        } else {
            hideStatus();
            // onPageFinished 时 getUrl() 才是最终地址（含重定向），这里必须刷一次，
            // 否则地址栏会停在“上一页”或用户刚敲的那串字上。
            updateToolbarState();
            // 页面标题也是这一刻才准：历史记录里补上，别只剩一串网址
            try {
                String u = web.getUrl();
                String t = web.getTitle();
                if (u != null && t != null && !t.trim().isEmpty()) store.updateVisitTitle(u, t);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPageVisible(BrowserWebView web) {
        if (!isActiveTab(web)) return;
        // 首屏画出来了就收掉提示：挡着画面不让看才是最慢的体验
        hideStatus();
        applyNightMode(web);
        if (web.isHomePage()) pushHomeData();
        updateToolbarState();
        // 页面稳定 0.8s 后抓一张缩略图，切标签时卡片里就有画面
        captureTabThumb(web, 1600, null);
    }

    @Override
    public void onPageVisited(BrowserWebView web, String url) {
        String title = null;
        try {
            title = web.getTitle();
        } catch (Throwable ignored) {
        }
        store.recordVisit(url, title);
        if (isActiveTab(web)) updateToolbarState();
    }

    @Override
    public void onPageError(BrowserWebView web, String message) {
        if (!isActiveTab(web)) return;
        showStatus(getString(R.string.page_error, message), 0);
    }

    @Override
    public void onNativeBack(BrowserWebView web, boolean doublePress) {
        if (!isActiveTab(web)) return;
        // 原生全屏还开着（网页那边没接住返回键时的兜底）：先把全屏退掉
        if (fullscreenView != null) {
            onFullscreenExit(web);
            return;
        }
        // 连按两下返回 = 长按返回：直接关标签页
        if (doublePress) {
            onExitRequest(true);
            return;
        }
        // 短按返回：先退这个标签页里的上一页（浏览器的习惯就是这样）
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        // 没有上一页了 → 关掉这个标签页（回到打开它之前那一页）
        if (closeCurrentTab()) return;
        // 只剩首屏：不关，先问一次要不要退出
        onExitRequest(false);
    }

    @Override
    public void onNewWindowRequest(BrowserWebView web, Message resultMsg) {
        // 后台标签里的广告 window.open 不能抢走前台：不是当前标签就丢掉
        if (!isActiveTab(web)) {
            try {
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(null);
                resultMsg.sendToTarget();
            } catch (Throwable ignored) {
            }
            return;
        }
        BrowserWebView child = newWindowTab();
        try {
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(child);
            resultMsg.sendToTarget();
        } catch (Throwable t) {
            Log.w(TAG, "转交新窗口失败", t);
        }
    }

    @Override
    public void onNewTabRequest(BrowserWebView web, String url) {
        // 首屏里的 http 跳转：一律开成新标签页，不能把首屏顶掉
        openTabFromWeb(web, url);
    }

    @Override
    public void onFullscreen(BrowserWebView web, View view, WebChromeClient.CustomViewCallback callback) {
        // 后台标签的视频不许盖住当前页面
        if (!isActiveTab(web)) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        if (fullscreenView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        fullscreenOwner = web;
        fullscreenView = view;
        fullscreenCallback = callback;
        host.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyImmersive();
    }

    @Override
    public void onFullscreenExit(BrowserWebView web) {
        // 只有全屏的发起者能退出全屏，别的标签不许把当前全屏收掉
        if (fullscreenView == null || (fullscreenOwner != null && web != fullscreenOwner)) return;
        fullscreenView.setVisibility(View.GONE);
        root.removeView(fullscreenView);
        fullscreenView = null;
        fullscreenOwner = null;
        host.setVisibility(View.VISIBLE);
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
        BrowserWebView active = activeTab();
        if (active != null) active.requestFocus();
        applyImmersive();
    }

    /* ---------------------------------------------------------------- 状态 -- */

    private final Runnable hideStatusTask = this::hideStatus;

    private void showStatus(String text, long autoHideMs) {
        if (status == null) return;
        status.removeCallbacks(hideStatusTask);
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        // 整页层是后来才加进 root 的，不 bringToFront 的话提示会被它盖住
        status.bringToFront();
        if (autoHideMs > 0) status.postDelayed(hideStatusTask, autoHideMs);
    }

    private void hideStatus() {
        if (status == null) return;
        status.removeCallbacks(hideStatusTask);
        status.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        // 整页（历史/书签/下载/标签/设置）先关页面
        if (pageVisible()) {
            closePage();
            return;
        }
        // 正常情况下返回键在 dispatchKeyEvent 里就被处理掉了，这里只是兜底
        Log.i(TAG, "onBackPressed fallback");
        BrowserWebView web = activeTab();
        if (web != null) {
            onNativeBack(web, false);
            return;
        }
        super.onBackPressed();
    }

    /**
     * 功能页的横向翻页容器：两页并排在同一条轨道上，
     * 手指横拖时轨道跟手走，抬手按拖动距离决定停在哪一页；纵向和点击都不拦。
     */
    private class SheetPager extends FrameLayout {
        private float downX;
        private float downY;
        private boolean dragging;
        private int lastX;

        SheetPager(android.content.Context c) {
            super(c);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    lastX = (int) ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    if (sheetTrack != null) sheetTrack.animate().cancel();
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!dragging && Math.abs(dx) > px(0.02f)
                            && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;
                        lastX = (int) ev.getX();
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (sheetTrack == null) return false;
            float w = getWidth();
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    lastX = (int) ev.getX();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    int x = (int) ev.getX();
                    float delta = x - lastX;
                    lastX = x;
                    if (w <= 0) return true;
                    float tx = sheetTrack.getTranslationX() + delta;
                    float min = -w * (sheetTrack.getChildCount() - 1);
                    if (tx > 0) tx = tx * 0.35f;
                    if (tx < min) tx = min + (tx - min) * 0.35f;
                    sheetTrack.setTranslationX(tx);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    float dx = ev.getX() - downX;
                    int target = sheetPageIndex;
                    if (w > 0) {
                        if (Math.abs(dx) > w * 0.12f) {
                            target = dx < 0 ? 1 : 0;
                        } else {
                            target = sheetTrack.getTranslationX() < -w / 2f ? 1 : 0;
                        }
                    }
                    dragging = false;
                    goSheetPage(target, true);
                    return true;
                }
                default:
                    return true;
            }
        }
    }
}
