package com.carweb.tvb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.graphics.Color;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.json.JSONObject;

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

    private final ArrayDeque<BrowserWebView> tabs = new ArrayDeque<>();
    /** 首屏那一个，永远留在栈底 */
    private BrowserWebView homeView;

    private SiteStore store;

    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private long lastExitAt = 0L;

    /* ---- 车机工具条（B 线新增，见 buildChrome） ---- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        store = new SiteStore(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        host = new FrameLayout(this);
        buildChrome();
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xCC000000);
        status.setPadding(36, 18, 36, 18);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusParams.bottomMargin = Math.round(getResources().getDisplayMetrics().density * 28);
        root.addView(status, statusParams);

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
    private GlyphIcon gBack;
    private GlyphIcon gFwd;
    private GlyphIcon gStar;
    private int screenW;
    private boolean addrEditing = false;

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

        ImageView bBack = makeBtn(GlyphIcon.BACK, iconPad);
        gBack = (GlyphIcon) bBack.getDrawable();
        ImageView bFwd = makeBtn(GlyphIcon.FORWARD, iconPad);
        gFwd = (GlyphIcon) bFwd.getDrawable();
        ImageView bHome = makeBtn(GlyphIcon.HOME, iconPad);
        ImageView bTabs = makeBtn(GlyphIcon.TABS, iconPad);
        btnStar = makeBtn(GlyphIcon.STAR, iconPad);
        gStar = (GlyphIcon) btnStar.getDrawable();
        ImageView bReload = makeBtn(GlyphIcon.REFRESH, iconPad);
        ImageView bMenu = makeBtn(GlyphIcon.MENU, iconPad);

        toolbar.addView(bBack, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bFwd, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bHome, new LinearLayout.LayoutParams(btnW, tbH));
        toolbar.addView(bTabs, new LinearLayout.LayoutParams(btnW, tbH));

        addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setTextColor(0xFFE8EDF2);
        addr.setHintTextColor(0xFF7A8794);
        addr.setHint("输入网址或搜索");
        addr.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setImeOptions(EditorInfo.IME_ACTION_GO);
        addr.setPadding(pad, 0, pad, 0);
        addr.setSelectAllOnFocus(true);
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
                showTabs();
            }
        });
        bMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
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

    private ImageView makeBtn(int glyph, int iconPad) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new GlyphIcon(glyph, 0xFFE8EDF2));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setPadding(iconPad, iconPad, iconPad, iconPad);
        v.setClickable(true);
        v.setFocusable(false);
        return v;
    }

    /** 关掉所有网页标签，回到首屏那一页 */
    private void goHomeTab() {
        hideKeyboard();
        while (tabs.size() > 1) {
            if (!closeCurrentTab()) break;
        }
        BrowserWebView home = homeView;
        if (home != null) {
            home.loadUrl(HOME_URL);
            home.requestFocus();
        }
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
    static String normalizeInput(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.length() == 0) return null;
        String low = s.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("file://") || low.startsWith("about:")) {
            return s;
        }
        if (!s.contains(" ") && s.matches("[\\w\\-.:]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?")) {
            return "https://" + s;
        }
        return "https://www.baidu.com/s?wd=" + Uri.encode(s);
    }

    private void openFromAddressBar(String text) {
        String url = normalizeInput(text);
        if (url == null) return;
        hideKeyboard();
        addr.clearFocus();
        BrowserWebView w = activeTab();
        if (w == null) return;
        if (w.isHomePage()) {
            openTab(url);          // 首屏上开新标签，保住首屏
        } else {
            w.loadUrl(url);        // 网页上原地跳转（返回键还能回来）
        }
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

    /* ---------------------------------------------------------- 标签页 -- */
    /*
     * 上游有标签栈（最多 5 个、首屏永驻栈底），但车机上没有任何入口能看到它 ——
     * 用户只能靠"返回"一层层退。这里补一个标签列表，点一下就切。
     */
    private void showTabs() {
        final BrowserWebView[] arr = tabs.toArray(new BrowserWebView[0]);
        if (arr.length == 0) return;

        String[] items = new String[arr.length];
        for (int i = 0; i < arr.length; i++) {
            BrowserWebView w = arr[i];
            String u = w.getUrl();
            String title = w.getTitle();
            if (u == null || u.startsWith(HOME_URL)) {
                title = "首屏";
            } else if (title == null || title.trim().length() == 0) {
                title = u;
            }
            if (title.length() > 36) title = title.substring(0, 36) + "…";
            boolean active = (i == arr.length - 1);
            items[i] = (active ? "● " : "　") + title;
        }

        new AlertDialog.Builder(this)
                .setTitle("标签页 · " + arr.length + " 个")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switchToTab(arr[which]);
                    }
                })
                .setPositiveButton("关闭当前", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int i) {
                        if (!closeCurrentTab()) showStatus("只剩首屏，不能关闭", 1600);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把某个标签页拨到栈顶（= 显示它） */
    private void switchToTab(BrowserWebView target) {
        if (target == null || target == activeTab()) return;
        BrowserWebView cur = activeTab();
        if (cur != null) {
            cur.onPause();
            cur.setVisibility(View.GONE);
        }
        tabs.remove(target);
        tabs.addLast(target);
        target.setVisibility(View.VISIBLE);
        target.onResume();
        target.requestFocus();
        target.resetBackPress();
        updateToolbarState();
    }

    /* ------------------------------------------------------------ 菜单 -- */

    private void showMenu() {
        BrowserWebView w = activeTab();
        final boolean desktop = (w == null) || w.isDesktopUa();
        final String[] items = new String[]{
                desktop ? "切换为手机版网页" : "切换为桌面版网页",
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
                            if (cur != null) cur.reload();
                        } else if (which == 2) {
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

    private void showAbout() {
        String ua = "";
        String engine = "";
        BrowserWebView w = activeTab();
        if (w != null) ua = w.getSettings().getUserAgentString();
        try {
            engine = WebView.getCurrentWebViewPackage() == null
                    ? "" : WebView.getCurrentWebViewPackage().versionName;
        } catch (Throwable ignored) {
        }
        String msg = "车机浏览器 B（基于 tv-browser 改造）\n\n"
                + "渲染内核：Android System WebView " + engine + "\n"
                + "当前标识：\n" + ua + "\n\n"
                + "长按网页 0.4 秒 = 鼠标悬停\n"
                + "长按首页卡片 = 收藏 / 删除 / 排序";
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    /* ------------------------------------------------------------ 下载 -- */

    @Override
    public void onDownload(BrowserWebView web, String url, String userAgent,
                           String contentDisposition, String mimeType, long contentLength) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req =
                    new DownloadManager.Request(android.net.Uri.parse(url));
            req.setMimeType(mimeType);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.setTitle(name);
            req.setDescription("正在下载");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                showStatus("开始下载：" + name, 2600);
            }
        } catch (Throwable t) {
            showStatus("下载失败：" + t.getMessage(), 2600);
        }
    }

    /* ------------------------------------------------------------ 首屏 -- */

    private void openHome() {
        BrowserWebView web = createTabView();
        homeView = web;
        tabs.addLast(web);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(HOME_URL);
        web.requestFocus();
    }

    /** 把收藏 / 最近打开 / 搜索引擎推给首屏 */
    void pushHomeData() {
        BrowserWebView home = homeView;
        if (home == null) return;
        String json = store.toJson();
        String js = "window.tvHome && window.tvHome.setData(" + JSONObject.quote(json) + ")";
        home.evaluateJavascript(js, null);
    }

    /* ------------------------------------------------------------ 标签页 -- */

    BrowserWebView activeTab() {
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
            tabs.remove(victim);
            host.removeView(victim);
            victim.destroy();
        }
        tabs.addLast(web);
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
        BrowserWebView cur = tabs.peekLast();
        if (cur == homeView) return false;
        tabs.pollLast();
        if (cur != null) {
            cur.onPause();
            host.removeView(cur);
            cur.destroy();
        }
        BrowserWebView prev = activeTab();
        if (prev != null) {
            prev.resetBackPress();     // 换了一页：别让"连按两下返回"接着往下关标签页
            prev.setVisibility(View.VISIBLE);
            prev.onResume();
            prev.requestFocus();
        }
        if (prev != null && prev.isHomePage()) pushHomeData();
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

    /** 首屏调完「移动位置」把收藏顺序交回来 —— 顺序已经生效在页面上了，不用再回推 */
    void saveFavoritesFromHome(String json) {
        store.replaceFavorites(json);
    }

    /* ------------------------------------------------------------- 生命周期 -- */

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
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
        for (BrowserWebView web : tabs) {
            host.removeView(web);
            web.destroy();
        }
        tabs.clear();
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
    public void onPageLoading(BrowserWebView web, boolean loading) {
        if (!isActiveTab(web)) return;
        if (loading) {
            // 有时间上限：页面一直不 onPageFinished 也不会把加载提示糊在屏幕上
            showStatus(getString(R.string.loading), LOADING_TIMEOUT_MS);
        } else {
            hideStatus();
        }
    }

    @Override
    public void onPageVisible(BrowserWebView web) {
        if (!isActiveTab(web)) return;
        // 首屏画出来了就收掉提示：挡着画面不让看才是最慢的体验
        hideStatus();
        if (web.isHomePage()) pushHomeData();
        updateToolbarState();
    }

    @Override
    public void onPageVisited(BrowserWebView web, String url) {
        store.remember(url);
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
        if (fullscreenView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;
        host.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyImmersive();
    }

    @Override
    public void onFullscreenExit(BrowserWebView web) {
        if (fullscreenView == null) return;
        fullscreenView.setVisibility(View.GONE);
        root.removeView(fullscreenView);
        fullscreenView = null;
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
        if (autoHideMs > 0) status.postDelayed(hideStatusTask, autoHideMs);
    }

    private void hideStatus() {
        if (status == null) return;
        status.removeCallbacks(hideStatusTask);
        status.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        // 正常情况下返回键在 dispatchKeyEvent 里就被处理掉了，这里只是兜底
        Log.i(TAG, "onBackPressed fallback");
        BrowserWebView web = activeTab();
        if (web != null) {
            onNativeBack(web, false);
            return;
        }
        super.onBackPressed();
    }
}
