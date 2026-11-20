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
 * 更新检查：R2 清单优先，GitHub API / Release 页面兜底。
 * R2 严格校验项目路径、大小与 SHA；GitHub 未提供摘要时不能声称哈希校验。
 * 下载仅接受 HTTPS 和显式域名白名单；所有来源失败则返回可读原因。
 * 版本按数字段比较，发布可用性与实际安装流程需要分别验证。
 */
public final class UpdateChecker {

    public static final String SOFTWARE = "car-browser-tvb";
    public static final String OWNER = "736368217";
    public static final String APPLICATION_ID = "com.carweb.tvb";

    /**
     * R2 为主发布源；请求失败后依次尝试 GitHub API 和 Release 页面。
     *
     * 兜底代码存在不代表 GitHub 资产与 R2 同步；
     * 当前发布身份及已知边界见 VERIFY.md。
     */
    public static final String CDN_BASE = "https://dl.736.de5.net";
    public static final String MANIFEST_URL = CDN_BASE + "/car-browser/latest.json";
    /** 手动下载页：列出 R2 上所有项目的最新包。 */
    public static final String DOWNLOAD_PAGE_URL = CDN_BASE + "/";

    /** releases/latest：仅作兜底（仓库公开，匿名可读）。 */
    public static final String API_LATEST =
            "https://api.github.com/repos/" + OWNER + "/" + SOFTWARE + "/releases/latest";
    public static final String RELEASE_PAGE_URL =
            "https://github.com/" + OWNER + "/" + SOFTWARE + "/releases/latest";

    private UpdateChecker() {
    }

    /** 统一的检查结果，来自 R2 清单、GitHub API 或发布页资产探测。 */
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
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
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", SOFTWARE + "-car/" + BuildConfig.VERSION_NAME);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            return parseManifest(readStream(conn.getInputStream()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Validate the same manifest contract for network responses and native acceptance. */
    static Result parseManifest(String body) throws Exception {
            JSONObject o = new JSONObject(body != null && body.startsWith("\uFEFF") ? body.substring(1) : body);
            String version = o.optString("version", "").trim();
            if (!version.matches("[0-9]+(?:\\.[0-9]+){0,3}"))
                throw new IllegalStateException("清单版本格式无效");
            String apk = o.optString("apk", "").trim();
            if (!apk.matches("car-browser/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk"))
                throw new IllegalStateException("清单 APK 路径无效");
            String url = CDN_BASE + "/" + apk;
            if (!isAllowedDownloadUrl(url)) throw new IllegalStateException("清单下载地址不可信");
            URL parsedUrl = new URL(url);
            if (!"dl.736.de5.net".equalsIgnoreCase(parsedUrl.getHost())
                    || parsedUrl.getQuery() != null || parsedUrl.getRef() != null)
                throw new IllegalStateException("清单下载地址无效");
            String name = parsedUrl.getPath().substring(parsedUrl.getPath().lastIndexOf('/') + 1);
            long size = o.optLong("size", 0L);
            if (size <= 0) throw new IllegalStateException("清单缺少有效文件大小");
            String sha = o.optString("sha256", "").trim().toLowerCase(Locale.US);
            if (!sha.matches("[0-9a-f]{64}")) throw new IllegalStateException("清单缺少有效 sha256");
            return new Result("v" + version, version, name, url, size, sha, o.optString("notes", ""));
    }
    /** Shared package integrity gate used by the update worker and native tests. */
    public static boolean verifyDownloadedArtifact(long expectedSize, String expectedSha,
                                                   long actualSize, String actualSha) {
        if (actualSha == null) return false;
        if (expectedSize > 0 && actualSize != expectedSize) return false;
        String expected = expectedSha == null ? "" : expectedSha.trim().toLowerCase(Locale.US);
        String actual = actualSha.trim().toLowerCase(Locale.US);
        if (expected.isEmpty()) return true;
        return expected.matches("[0-9a-f]{64}") && actual.matches("[0-9a-f]{64}")
                && expected.equals(actual);
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
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
                    head.setConnectTimeout(5000);
                    head.setReadTimeout(5000);
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
     * 下载域名白名单：只认 HTTPS + R2 分发域名及列明的 GitHub 资源域名。
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
