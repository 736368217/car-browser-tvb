package com.carweb.tvb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * GitHub Release 自动更新检查。
 *
 * 6.10 起仓库转为公开，所以匿名 GET 就能拿到 releases/latest：APK 里**不内嵌任何 token**，
 * 也不依赖已停用的 dl.z736.vip（服务器 22/443/4450 均不可达）。
 *
 * 约定：
 *   · 只认 HTTPS + GitHub 系域名，下载地址不接受其它站点；
 *   · tag 形如 v6.10，版本号按数字段比较（6.10 > 6.9，不是字符串比较）；
 *   · Release 里只挂 APK；API 若给出 digest，就用它做 sha256 校验。
 */
public final class UpdateChecker {

    public static final String SOFTWARE = "car-browser-tvb";
    public static final String OWNER = "736368217";
    public static final String APPLICATION_ID = "com.carweb.tvb";

    /**
     * 发布通道已从 GitHub Release 迁到 Cloudflare R2（自定义域名 dl.736.de5.net）。
     *
     * 客户端先读 R2 上的 latest.json；读不到才回落到 GitHub（过渡期兜底，
     * 老版本客户端仍走 GitHub，不会因此失联）。
     */
    public static final String CDN_BASE = "https://dl.736.de5.net";
    public static final String MANIFEST_URL = CDN_BASE + "/car-browser/latest.json";

    /** releases/latest：仅作兜底（仓库公开，匿名可读）。 */
    public static final String API_LATEST =
            "https://api.github.com/repos/" + OWNER + "/" + SOFTWARE + "/releases/latest";
    public static final String RELEASE_PAGE_URL =
            "https://github.com/" + OWNER + "/" + SOFTWARE + "/releases/latest";

    private UpdateChecker() {
    }

    /** 一次检查的结果。字段都来自 GitHub API，不做本地猜测。 */
    public static final class Result {
        public final String tag;
        public final String versionName;
        public final String apkName;
        public final String apkUrl;
        public final long size;
        /** 小写十六进制；API 没给就是空串（此时只校验大小）。 */
        public final String sha256;
        public final String notes;

        Result(String tag, String versionName, String apkName, String apkUrl,
               long size, String sha256, String notes) {
            this.tag = tag;
            this.versionName = versionName;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.size = size;
            this.sha256 = sha256;
            this.notes = notes;
        }

        public boolean isNewerThan(String currentVersionName) {
            return compareVersions(versionName, currentVersionName) > 0;
        }
    }

    /**
     * 数字段比较版本号：6.10 > 6.9，6.9 == 6.9.0。非数字后缀（-beta）忽略。
     * 返回 >0 表示 a 比 b 新。
     */
    public static int compareVersions(String a, String b) {
        int[] pa = numeric(a);
        int[] pb = numeric(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return va > vb ? 1 : -1;
        }
        return 0;
    }

    private static int[] numeric(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] parts = s.split("[^0-9]+");
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(p));
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** 阻断主线程请求：必须在工作线程调用。 */
    private static Result fetchFromApi() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_LATEST);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", SOFTWARE + "-car/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code == 404) throw new IllegalStateException("发布页没有 Release");
            if (code == 403) throw new IllegalStateException("GitHub 限流，稍后再试");
            if (code != 200) throw new IllegalStateException("HTTP " + code);

            String body = readAll(conn.getInputStream());
            JSONObject root = new JSONObject(body);
            String tag = root.optString("tag_name", "");
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            String notes = root.optString("body", "");

            JSONArray assets = root.optJSONArray("assets");
            if (assets == null || assets.length() == 0) {
                throw new IllegalStateException("Release 里没有资产");
            }
            String apkName = "";
            String apkUrl = "";
            long size = 0L;
            String sha = "";
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
                apkName = name;
                apkUrl = a.optString("browser_download_url", "");
                size = a.optLong("size", 0L);
                String digest = a.optString("digest", "");
                if (digest.toLowerCase(Locale.US).startsWith("sha256:")) {
                    sha = digest.substring(7).trim().toLowerCase(Locale.US);
                }
                break;
            }
            if (apkName.isEmpty() || !isAllowedDownloadUrl(apkUrl)) {
                throw new IllegalStateException("Release 里没有可用的 APK");
            }
            return new Result(tag, version, apkName, apkUrl, size, sha, notes);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 检查最新发布：R2 清单 → GitHub API → 发布页 HTML，逐级兜底。
     * 车机上偶发 DNS/限流不该让更新功能整个失效。
     */
    public static Result fetchLatest() throws Exception {
        Exception first;
        try {
            return fetchFromManifest();
        } catch (Exception e) {
            first = e;
        }
        Exception second;
        try {
            return fetchFromApi();
        } catch (Exception e) {
            second = e;
        }
        try {
            return fetchFromHtml();
        } catch (Exception e) {
            throw new IllegalStateException(first.getMessage()
                    + "；GitHub 兜底也失败：" + second.getMessage()
                    + "；发布页兜底也失败：" + e.getMessage());
        }
    }

    /** R2 清单：{version, apk, size, sha256, notes}，由 tools/publish_r2.ps1 生成。 */
    private static Result fetchFromManifest() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", SOFTWARE + "-car/" + BuildConfig.VERSION_NAME);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            String body = readStream(conn.getInputStream());
            org.json.JSONObject o = new org.json.JSONObject(body);
            String version = o.optString("version", "").trim();
            if (version.isEmpty()) throw new IllegalStateException("清单缺少 version");
            String apk = o.optString("apk", "").trim();
            if (apk.isEmpty()) throw new IllegalStateException("清单缺少 apk");
            String url = apk.startsWith("http://") || apk.startsWith("https://")
                    ? apk : CDN_BASE + "/" + apk;
            long size = o.optLong("size", 0L);
            String sha = o.optString("sha256", "").trim().toLowerCase(Locale.US);
            String notes = o.optString("notes", "");
            String name = apk.substring(apk.lastIndexOf('/') + 1);
            return new Result("v" + version, version, name, url, size, sha, notes);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    /** 兜底路径：跟着 releases/latest 的跳转取 tag，再按资产命名约定拼下载地址。 */
    private static Result fetchFromHtml() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASE_PAGE_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", SOFTWARE + "-car/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            String finalUrl = conn.getURL().toString();
            int idx = finalUrl.indexOf("/releases/tag/");
            if (idx < 0) throw new IllegalStateException("发布页没有 Release");
            String tag = finalUrl.substring(idx + "/releases/tag/".length()).trim();
            int cut = tag.indexOf('?');
            if (cut > 0) tag = tag.substring(0, cut);
            int slash = tag.indexOf('/');
            if (slash > 0) tag = tag.substring(0, slash);
            if (tag.isEmpty()) throw new IllegalStateException("发布页没有 Release");
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            // 资产命名以 car-browser-<版本>.apk 为准，同时兼容早期的 car-browser-tvb-<版本>.apk
            String[] candidates = new String[]{
                    "car-browser-" + version + ".apk",
                    SOFTWARE + "-" + version + ".apk"
            };
            String name = "";
            String assetUrl = "";
            long size = 0L;
            for (String candidate : candidates) {
                String url = "https://github.com/" + OWNER + "/" + SOFTWARE
                        + "/releases/download/" + tag + "/" + candidate;
                HttpURLConnection head = null;
                try {
                    head = (HttpURLConnection) new URL(url).openConnection();
                    head.setInstanceFollowRedirects(true);
                    head.setRequestMethod("HEAD");
                    head.setConnectTimeout(10000);
                    head.setReadTimeout(10000);
                    head.setRequestProperty("User-Agent", SOFTWARE + "-car/" + BuildConfig.VERSION_NAME);
                    int hc = head.getResponseCode();
                    if (hc >= 200 && hc < 400) {
                        name = candidate;
                        assetUrl = url;
                        int len = head.getContentLength();
                        if (len > 0) size = len;
                        break;
                    }
                } catch (Throwable ignored) {
                    // HEAD 不被 CDN 接受时继续试下一个候选
                } finally {
                    if (head != null) head.disconnect();
                }
            }
            if (name.isEmpty()) {
                throw new IllegalStateException("Release 里没有 " + candidates[0]);
            }
            return new Result(tag, version, name, assetUrl, size, "", "");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    /**
     * 域名白名单：只认 HTTPS + GitHub 系域名，不许被跳去任意站点。
     */
    public static boolean isAllowedDownloadUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URL u = new URL(value);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;
            if (u.getUserInfo() != null && !u.getUserInfo().isEmpty()) return false;
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return "dl.736.de5.net".equals(host)
                    || "github.com".equals(host)
                    || "objects.githubusercontent.com".equals(host)
                    || "release-assets.githubusercontent.com".equals(host)
                    || "raw.githubusercontent.com".equals(host);
        } catch (Exception e) {
            return false;
        }
    }
}
