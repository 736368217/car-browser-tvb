package com.carweb.tvb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.graphics.Color;
import android.content.DialogInterface;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.database.Cursor;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.Handler;
import android.os.Looper;
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
import java.io.File;

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
        uiHandler.postDelayed(addrSync, 500);
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
        buildSheet();
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
                showTabs();
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
    String normalizeInput(String input) {
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
        return searchUrlFor(s);
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
        String msg = "车览浏览器 " + BuildConfig.VERSION_NAME + "（B 线 · 基于 tv-browser 改造）\n\n"
                + "渲染内核：Android System WebView " + engine + "\n"
                + "当前标识：" + ua + "\n\n"
                + "长按网页 0.4 秒 = 鼠标悬停\n"
                + "长按首页卡片 = 编辑（拖动排序 / 点 − 删除）\n"
                + "地址栏：输入网址直接访问，输入文字直接搜索";

        // 车机上默认 AlertDialog 的字太小、灰底也不好看，这里自己搭面板
        TextView body = new TextView(this);
        body.setText(msg);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38f / 2880f));
        body.setTextColor(0xFFE6E9F0);
        body.setLineSpacing(px(14f / 2880f), 1f);

        final TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(40f / 2880f));
        ok.setTextColor(0xFF6FA8FF);
        ok.setGravity(Gravity.END);
        ok.setPadding(0, px(44f / 2880f), 0, 0);

        int pad = px(56f / 2880f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, px(32f / 2880f));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1F26);
        bg.setCornerRadius(px(30f / 2880f));
        bg.setStroke(Math.max(2, px(2f / 2880f)), 0xFF33383F);
        box.setBackground(bg);
        box.addView(body);
        box.addView(ok);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.show();
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            WindowManager.LayoutParams wlp = win.getAttributes();
            wlp.width = px(1560f / 2880f);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            win.setAttributes(wlp);
        }
    }

    /* ------------------------------------------------------------ 下载 -- */

    @Override
    public void onDownload(BrowserWebView web, String url, String userAgent,
                           String contentDisposition, String mimeType, long contentLength) {
        try {
            Log.i(TAG, "下载请求: " + url + " mime=" + mimeType);
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
            // 车机没有「下载」系统 App，也不一定有存储权限；放应用自己的下载目录最稳
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                long id = dm.enqueue(req);
                Log.i(TAG, "已加入下载队列 id=" + id);
                showStatus("开始下载：" + name, 2600);
            }
        } catch (Throwable t) {
            Log.e(TAG, "下载失败", t);
            showStatus("下载失败：" + t.getMessage(), 2600);
        }
    }

    /** 应用内下载列表：不依赖车机上有没有「下载」这个系统 App */
    private void showDownloads() {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) {
            showStatus("系统下载服务不可用", 1600);
            return;
        }
        final java.util.List<Long> ids = new java.util.ArrayList<>();
        final java.util.List<String> mimes = new java.util.ArrayList<>();
        final java.util.List<String> lines = new java.util.ArrayList<>();
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterByStatus(
                    DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING
                            | DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_SUCCESSFUL
                            | DownloadManager.STATUS_FAILED));
            while (c != null && c.moveToNext()) {
                long id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                String title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                String mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                String state;
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    state = total > 0 ? "已完成 " + mb(total) : "已完成";
                } else if (status == DownloadManager.STATUS_RUNNING) {
                    state = total > 0 ? "下载中 " + (done * 100 / total) + "%" : "下载中";
                } else if (status == DownloadManager.STATUS_PAUSED) {
                    state = "已暂停";
                } else if (status == DownloadManager.STATUS_FAILED) {
                    state = "失败";
                } else {
                    state = "等待中";
                }
                ids.add(id);
                mimes.add(mime == null ? "application/octet-stream" : mime);
                lines.add(title + "  ·  " + state);
            }
        } catch (Throwable t) {
            showStatus("读取下载列表失败", 1600);
            return;
        } finally {
            if (c != null) c.close();
        }

        if (lines.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("下载")
                    .setMessage("还没有下载文件。\n\n在网页里点下载链接，文件会保存到系统「下载」目录，"
                            + "并出现在这里。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("下载 · " + lines.size() + " 个")
                .setItems(lines.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openDownload(ids.get(which), mimes.get(which));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openDownload(long id, String mime) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            showStatus("文件还没下载完", 1600);
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (Throwable t) {
            showStatus("没有能打开这个文件的应用", 1600);
        }
    }

    /* ------------------------------------------------- 功能页（底部面板） -- */
    /*
     * 原来点菜单是一个竖排 AlertDialog 列表，又窄又丑。
     * 改成参考 Via 的做法：从底部升起一页功能网格 —— 图标 + 文字、5 列两行，
     * 底部有分页圆点、电源键和翻页箭头。目标大、扫一眼就能点到。
     */

    private FrameLayout sheetLayer;
    private View sheetPage1;
    private View sheetPage2;
    private View sheetDot1;
    private View sheetDot2;
    private int sheetPageIndex = 0;
    private boolean nightMode = false;
    private boolean toolbarHidden = false;

    private static final String[][] SHEET_PAGE1 = {
            {"主页", "3", "home"},
            {"后退", "0", "back"},
            {"前进", "1", "forward"},
            {"刷新", "2", "reload"},
            {"标签页", "7", "tabs"},
            {"添加书签", "4", "bookmark"},
            {"历史", "10", "history"},
            {"下载", "8", "download"},
            {"分享", "11", "share"},
            {"电脑模式", "12", "desktop"}
    };

    private static final String[][] SHEET_PAGE2 = {
            {"夜间模式", "13", "night"},
            {"全屏", "14", "fullscreen"},
            {"清除缓存", "15", "clear"},
            {"搜索引擎", "9", "engine"},
            {"关于", "5", "about"},
            {"退出", "16", "exit"}
    };

    private void buildSheet() {
        sheetLayer = new FrameLayout(this);
        sheetLayer.setVisibility(View.GONE);

        View scrim = new View(this);
        scrim.setBackgroundColor(0xC8000000);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSheet();
            }
        });
        sheetLayer.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = px(0.012f);
        float r = px(0.016f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF14181F);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setPadding(pad, pad, pad, pad / 2);

        FrameLayout pages = new FrameLayout(this);
        sheetPage1 = buildSheetPage(SHEET_PAGE1);
        sheetPage2 = buildSheetPage(SHEET_PAGE2);
        sheetPage2.setVisibility(View.GONE);
        pages.addView(sheetPage1);
        pages.addView(sheetPage2);
        panel.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad / 2, pad / 4, pad / 2, pad / 4);

        int btn = px(0.038f);
        ImageView power = new ImageView(this);
        power.setImageDrawable(new GlyphIcon(GlyphIcon.POWER, 0xFF8B95A5));
        power.setPadding(btn / 4, btn / 4, btn / 4, btn / 4);
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSheet();
            }
        });
        bar.addView(power, new LinearLayout.LayoutParams(btn, btn));

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
        // 点圆点也能翻页（比只有右下角小箭头好点）
        dots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchSheetPage();
            }
        });

        sheetDot1 = makeDot(true);
        sheetDot2 = makeDot(false);
        LinearLayout.LayoutParams d1 = new LinearLayout.LayoutParams(ds, ds);
        d1.rightMargin = dm;
        dots.addView(sheetDot1, d1);
        dots.addView(sheetDot2, new LinearLayout.LayoutParams(ds, ds));

        ImageView arrow = new ImageView(this);
        GlyphIcon chevron = new GlyphIcon(GlyphIcon.FORWARD, 0xFF8B95A5);
        arrow.setImageDrawable(chevron);
        arrow.setRotation(90f);
        arrow.setPadding(btn / 4, btn / 4, btn / 4, btn / 4);
        arrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchSheetPage();
            }
        });
        bar.addView(arrow, new LinearLayout.LayoutParams(btn, btn));

        panel.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sheetLayer.addView(panel, panelLp);
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

    private void switchSheetPage() {
        sheetPageIndex = 1 - sheetPageIndex;
        boolean first = sheetPageIndex == 0;
        sheetPage1.setVisibility(first ? View.VISIBLE : View.GONE);
        sheetPage2.setVisibility(first ? View.GONE : View.VISIBLE);
        sheetDot1.setBackground(circle(first ? 0xFFE8EDF2 : 0xFF3A4350));
        sheetDot2.setBackground(circle(first ? 0xFF3A4350 : 0xFFE8EDF2));
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

    private void showSheet() {
        sheetPageIndex = 0;
        sheetPage1.setVisibility(View.VISIBLE);
        sheetPage2.setVisibility(View.GONE);
        sheetDot1.setBackground(circle(0xFFE8EDF2));
        sheetDot2.setBackground(circle(0xFF3A4350));
        sheetLayer.setVisibility(View.VISIBLE);
        sheetLayer.setTranslationY(px(0.5f));
        sheetLayer.animate().translationY(0f).setDuration(180).start();
    }

    private void hideSheet() {
        if (sheetLayer == null || sheetLayer.getVisibility() != View.VISIBLE) return;
        sheetLayer.animate().translationY(px(0.5f)).setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        sheetLayer.setVisibility(View.GONE);
                    }
                }).start();
    }

    private void runSheetAction(String action) {
        BrowserWebView w = activeTab();
        if ("home".equals(action)) {
            goHomeTab();
        } else if ("back".equals(action)) {
            if (w != null) onNativeBack(w, false);
        } else if ("forward".equals(action)) {
            if (w != null && w.canGoForward()) w.goForward();
        } else if ("reload".equals(action)) {
            if (w != null) w.reload();
        } else if ("tabs".equals(action)) {
            showTabs();
        } else if ("bookmark".equals(action)) {
            toggleBookmark();
        } else if ("history".equals(action)) {
            showHistory();
        } else if ("download".equals(action)) {
            showDownloads();
        } else if ("share".equals(action)) {
            shareCurrent();
        } else if ("desktop".equals(action)) {
            if (w != null) {
                w.setDesktopUa(!w.isDesktopUa());
                showStatus(w.isDesktopUa() ? "电脑版网页：开" : "电脑版网页：关", 1600);
            }
        } else if ("night".equals(action)) {
            toggleNightMode();
        } else if ("fullscreen".equals(action)) {
            setFullscreen(!toolbarHidden);
        } else if ("clear".equals(action)) {
            if (w != null) {
                long before = cacheSize();
                w.clearCache(true);
                showStatus(before > 0 ? "缓存已清除（约 " + mb(before) + "）" : "缓存已清除", 1600);
            }
        } else if ("engine".equals(action)) {
            showEnginePicker();
        } else if ("about".equals(action)) {
            showAbout();
        } else if ("exit".equals(action)) {
            finish();
        }
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
        java.util.List<String> list = store.recent();
        if (list.isEmpty()) {
            showStatus("还没有浏览记录", 1600);
            return;
        }
        final String[] arr = list.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("历史记录")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openFromAddressBar(arr[which]);
                    }
                })
                .show();
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

    /** 长按卡片「编辑」：改名字 / 改网址，位置不变 */
    void editFavoriteFromHome(final String url) {
        final String canon = SiteStore.canonical(url);
        String custom = store.titleOf(url);
        final String currentName = (custom == null) ? "" : custom;

        int pad = Math.max(28, Math.round(getResources().getDisplayMetrics().widthPixels * 0.012f));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 3, pad, 0);

        final EditText nameIn = new EditText(this);
        nameIn.setHint("名称（留空则用站点默认名）");
        nameIn.setText(currentName);
        nameIn.setTextSize(18);
        box.addView(nameIn);

        final EditText urlIn = new EditText(this);
        urlIn.setHint("网址");
        urlIn.setText(canon);
        urlIn.setTextSize(16);
        box.addView(urlIn);

        new AlertDialog.Builder(this)
                .setTitle("编辑站点")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String newName = nameIn.getText().toString().trim();
                        String newUrl = urlIn.getText().toString().trim();
                        if (!SiteStore.isHttpUrl(newUrl)) {
                            showStatus("网址需要以 http(s):// 开头", 2400);
                            return;
                        }
                        store.rename(url, newName, newUrl);
                        pushHomeData();
                        showStatus("已保存", 1500);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** 设置里的搜索引擎选择 */
    private void showEnginePicker() {
        final String[] ids = {"baidu", "bing", "sogou", "google"};
        final String[] names = {"百度", "必应", "搜狗", "谷歌"};
        int checked = 0;
        String cur = store.engine();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(cur)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("搜索引擎")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        store.setEngine(ids[which]);
                        pushHomeData();
                        d.dismiss();
                        showStatus("搜索引擎：" + names[which], 1500);
                    }
                })
                .show();
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
        uiHandler.removeCallbacks(addrSync);
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
