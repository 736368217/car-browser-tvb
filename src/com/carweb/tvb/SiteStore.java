package com.carweb.tvb;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏 / 最近打开 / 搜索引擎选择的存储。
 *
 * 只存 URL：名称、颜色、图标都由首屏自己按域名推（见 assets/home.js）——
 * 名字要中文的、图标要站点自己的 favicon，这些是"显示"的事，
 * 放在原生这边只会变成一堆要和首页同步的字面量。
 *
 * 纯逻辑的那几个方法（canonical / isHttpUrl）是静态的，方便单测。
 */
public final class SiteStore {

    /** 收藏上限：超过之后首页长按收藏会提示"收藏已满" */
    public static final int MAX_FAVORITES = 60;
    /** 「最近打开」还留着：历史页与首页数据都用它兜底 */
    public static final int MAX_RECENT = 60;
    /** 历史记录上限（正经历史页，不是首页那排「最近」） */
    public static final int MAX_VISITS = 500;

    public static final String DEFAULT_ENGINE = "baidu";

    private static final String PREF = "tv_browser";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_RECENT = "recent";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_VISITS = "visits";

    /**
     * 第一次运行时预置的收藏。
     * 中文名和品牌色在 assets/home.js 的 NAMES / TINTS 里按域名匹配。
     */
    /*
     * 默认只放三个：百度、谷歌、YouTube。
     * 其余站点（bilibili/微博/知乎/淘宝/京东/腾讯视频/爱奇艺/优酷/豆瓣/网易云）
     * 只作为图标与收藏能力的验证样本，不默认塞给用户。
     */
    public static final List<String> DEFAULT_FAVORITES = Arrays.asList(
            "https://www.baidu.com",
            "https://www.google.com",
            "https://www.youtube.com",
            "https://www.bilibili.com",
            "https://www.zhihu.com",
            "https://www.taobao.com",
            "https://weibo.com",
            "https://www.douban.com",
            "https://www.jd.com",
            "https://www.youku.com",
            "https://www.iqiyi.com",
            "https://v.qq.com",
            "https://music.163.com"
    );

    private final SharedPreferences prefs;

    public SiteStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE));
    }

    /** 单元测试直接塞一个假了的 SharedPreferences 进来 */
    SiteStore(SharedPreferences prefs) {
        this.prefs = prefs;
        seedIfNeeded();
    }

    /* ---------------------------------------------------------- 纯逻辑 -- */

    /**
     * URL 归一化：去掉首尾空白和 #片段、去掉根路径结尾的斜杠。
     * 用于"同一个网站不要记两条"。
     */
    public static String canonical(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.isEmpty()) return "";
        int frag = s.indexOf('#');
        if (frag >= 0) s = s.substring(0, frag).trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0 && s.endsWith("/")) {
            String rest = s.substring(scheme + 3);
            // 只有一个结尾斜杠（根路径）才去掉；/a/ 这种保留
            if (rest.indexOf('/') == rest.length() - 1) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String s = url.trim().toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** 从 URL 里取主机名，取不到返回 "" */
    public static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }

    /* ------------------------------------------------------------ 收藏 -- */

    public List<String> favorites() {
        return readList(KEY_FAVORITES);
    }

    public boolean isFavorite(String url) {
        String u = canonical(url);
        return !u.isEmpty() && favorites().contains(u);
    }

    /**
     * 加收藏。
     * @return false 只有一种情况：收藏满了
     */
    public boolean addFavorite(String url) {
        String u = canonical(url);
        if (u.isEmpty()) return false;
        List<String> list = favorites();
        if (list.contains(u)) return true;
        if (list.size() >= MAX_FAVORITES) return false;
        list.add(u);
        writeList(KEY_FAVORITES, list);
        return true;
    }

    public boolean removeFavorite(String url) {
        String u = canonical(url);
        List<String> list = favorites();
        if (u.isEmpty() || !list.remove(u)) return false;
        writeList(KEY_FAVORITES, list);
        return true;
    }

    /**
     * 整份替换收藏 —— 首屏调完「移动位置」会把排好的顺序交回来。
     *
     * 会重新做一次归一化和去重，并且不越过上限（页面递上来的东西不能全信）。
     *
     * @return 真的写进去了返回 true
     */
    public boolean replaceFavorites(String json) {
        if (json == null || json.isEmpty()) return false;
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && list.size() < MAX_FAVORITES; i++) {
                String u = canonical(arr.optString(i, ""));
                if (!u.isEmpty() && !list.contains(u)) list.add(u);
            }
        } catch (JSONException e) {
            // 递上来的不是合法 JSON：宁可不改，也不要清空用户的收藏
            return false;
        }
        writeList(KEY_FAVORITES, list);
        return true;
    }

    /* -------------------------------------------------------- 最近打开 -- */

    /** 记一条浏览记录：已经在里面的提到最前面，超出上限的丢掉最老的 */
    public List<String> recent() {
        return readList(KEY_RECENT);
    }

    public void remember(String url) {
        String u = canonical(url);
        if (u.isEmpty()) return;
        List<String> list = recent();
        list.remove(u);
        list.add(0, u);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        writeList(KEY_RECENT, list);
    }

    public boolean forget(String url) {
        String u = canonical(url);
        List<String> list = recent();
        if (u.isEmpty() || !list.remove(u)) return false;
        writeList(KEY_RECENT, list);
        return true;
    }

    /** 收藏和最近打开里都不要再出现这个网站（测试和重置用） */
    public void forgetEverywhere(String url) {
        removeFavorite(url);
        forget(url);
    }

    /* -------------------------------------------------------- 历史记录 -- */
    /*
     * 「最近打开」只存 URL，够首页用；历史记录页要标题和时间，所以另存一张表。
     * 同一个 URL 只留最近一次（和浏览器一致），最多 500 条。
     */

    public static final class Visit {
        public final String url;
        public final String title;
        public final long time;

        Visit(String url, String title, long time) {
            this.url = url;
            this.title = title;
            this.time = time;
        }
    }

    public List<Visit> visits() {
        List<Visit> out = new ArrayList<>();
        String raw = prefs.getString(KEY_VISITS, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = canonical(o.optString("u", ""));
                if (u.isEmpty()) continue;
                out.add(new Visit(u, o.optString("t", ""), o.optLong("ts", 0L)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** 记一条历史：同 URL 去重后放到最前面，再顺手更新「最近打开」 */
    public void recordVisit(String url, String title) {
        String u = canonical(url);
        if (u.isEmpty()) return;
        List<Visit> list = visits();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).url.equals(u)) list.remove(i);
        }
        String t = title == null ? "" : title.trim();
        list.add(0, new Visit(u, t, System.currentTimeMillis()));
        while (list.size() > MAX_VISITS) list.remove(list.size() - 1);
        writeVisits(list);
        remember(u);
    }

    public boolean deleteVisit(String url) {
        String u = canonical(url);
        if (u.isEmpty()) return false;
        List<Visit> list = visits();
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).url.equals(u)) {
                list.remove(i);
                removed = true;
            }
        }
        if (removed) writeVisits(list);
        return removed;
    }

    /** 页面加载完才拿得到真标题：把这条历史补上，别在列表里只剩网址 */
    public void updateVisitTitle(String url, String title) {
        String u = canonical(url);
        String t = title == null ? "" : title.trim();
        if (u.isEmpty() || t.isEmpty()) return;
        List<Visit> list = visits();
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Visit v = list.get(i);
            if (v.url.equals(u) && !t.equals(v.title)) {
                list.set(i, new Visit(v.url, t, v.time));
                changed = true;
                break;
            }
        }
        if (changed) writeVisits(list);
    }

    public void clearVisits() {
        prefs.edit().remove(KEY_VISITS).putString(KEY_RECENT, "[]").apply();
    }

    private void writeVisits(List<Visit> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Visit v : list) {
                JSONObject o = new JSONObject();
                o.put("u", v.url);
                o.put("t", v.title);
                o.put("ts", v.time);
                arr.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_VISITS, arr.toString()).apply();
    }

    /* ------------------------------------------------------ 自定义名称 -- */
    /*
     * 长按卡片「编辑」可以给站点起个自己的名字。
     * 存成 {url: name} 的一张小表；网址改了，名字跟着搬过去。
     */
    private static final String KEY_TITLES = "titles";

    public Map<String, String> titles() {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = prefs.getString(KEY_TITLES, null);
        if (raw == null) return out;
        try {
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.optString(k, ""));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public String titleOf(String url) {
        return titles().get(canonical(url));
    }

    /** 编辑一张收藏卡：改名 / 改网址 / 两者都改。位置保持不变。 */
    public void rename(String oldUrl, String newName, String newUrl) {
        String from = canonical(oldUrl);
        String to = canonical(newUrl);
        if (from.isEmpty() || to.isEmpty()) return;

        List<String> list = favorites();
        int idx = list.indexOf(from);
        if (idx >= 0 && !from.equals(to)) {
            list.set(idx, to);
            writeList(KEY_FAVORITES, list);
        }

        Map<String, String> map = titles();
        String oldName = map.remove(from);
        String name = (newName == null || newName.trim().isEmpty()) ? oldName : newName.trim();
        if (name != null && !name.isEmpty()) map.put(to, name);

        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : map.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_TITLES, o.toString()).apply();
    }
    /* ------------------------------------------------------ 搜索引擎 -- */

    public String engine() {
        String id = prefs.getString(KEY_ENGINE, DEFAULT_ENGINE);
        return id == null || id.isEmpty() ? DEFAULT_ENGINE : id;
    }

    public void setEngine(String id) {
        if (id == null || id.isEmpty()) return;
        prefs.edit().putString(KEY_ENGINE, id).apply();
    }

    /* ------------------------------------------------------------ 输出 -- */

    /** 推给首页的那份数据 */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("favorites", new JSONArray(favorites()));
            o.put("recent", new JSONArray(recent()));
            o.put("engine", engine());
            o.put("titles", new JSONObject(titles()));
            return o.toString();
        } catch (JSONException e) {
            return "{\"favorites\":[],\"recent\":[],\"engine\":\"" + DEFAULT_ENGINE + "\"}";
        }
    }

    /* ------------------------------------------------------------ 内部 -- */

    private void seedIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return;
        List<String> seed = new ArrayList<>();
        for (String url : DEFAULT_FAVORITES) {
            String u = canonical(url);
            if (!u.isEmpty() && !seed.contains(u) && seed.size() < MAX_FAVORITES) seed.add(u);
        }
        prefs.edit()
                .putString(KEY_FAVORITES, new JSONArray(seed).toString())
                .putBoolean(KEY_SEEDED, true)
                .apply();
    }

    private List<String> readList(String key) {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(key, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (v != null && !v.isEmpty()) out.add(v);
            }
        } catch (JSONException ignored) {
            // 存坏了就当空列表，不要让首页挂掉
        }
        return out;
    }

    private void writeList(String key, List<String> list) {
        prefs.edit().putString(key, new JSONArray(list).toString()).apply();
    }
}
