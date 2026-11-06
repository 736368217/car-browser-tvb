package com.carweb.tvb;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 车览 B 线的自动更新清单检查。
 *
 * 与工作区其他车机软件保持同一约定：固定读取自己产品的 latest.json，
 * 只检查、不静默下载、不静默安装；下载地址必须落在可信 HTTPS 域名上。
 * 私有 GitHub 仓库的 Release API 需要 token，所以 APK 里不放任何凭据，
 * 也不把私有 Release 当匿名数据源。
 */
public final class UpdateChecker {

    public static final String SOFTWARE = "car-browser-tvb";
    public static final String APPLICATION_ID = "com.carweb.tvb";
    public static final String FEED_URL =
            "https://dl.z736.vip/" + SOFTWARE + "/latest.json";
    public static final String RELEASE_PAGE_URL =
            "https://github.com/736368217/" + SOFTWARE + "/releases/latest";
    public static final String TRUSTED_HOST = "dl.z736.vip";

    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_FEED_BYTES = 64 * 1024;

    private UpdateChecker() {
    }

    public static final class Result {
        public final String currentVersion;
        public final int currentVersionCode;
        public final String latestVersion;
        public final int latestVersionCode;
        public final String releasedAt;
        public final String fileName;
        public final long sizeBytes;
        public final String sha256;
        public final String downloadUrl;

        Result(String currentVersion, int currentVersionCode, String latestVersion,
               int latestVersionCode, String releasedAt, String fileName,
               long sizeBytes, String sha256, String downloadUrl) {
            this.currentVersion = currentVersion;
            this.currentVersionCode = currentVersionCode;
            this.latestVersion = latestVersion;
            this.latestVersionCode = latestVersionCode;
            this.releasedAt = releasedAt;
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.downloadUrl = downloadUrl;
        }

        public boolean hasUpdate() {
            int cmp = compareVersions(latestVersion, currentVersion);
            return cmp > 0 || (latestVersionCode > currentVersionCode && cmp >= 0);
        }
    }

    /** 用中文把网络/清单错误直接告诉用户，不让「检查失败」成为死路。 */
    public static final class UpdateException extends Exception {
        public UpdateException(String message) {
            super(message);
        }

        public UpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Result check(Context context) throws UpdateException {
        String current = BuildConfig.VERSION_NAME;
        int currentCode = BuildConfig.VERSION_CODE;
        try {
            @SuppressWarnings("deprecation")
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pi != null) {
                if (pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                    current = pi.versionName.trim();
                }
                if (pi.versionCode > 0) currentCode = pi.versionCode;
            }
        } catch (Throwable ignored) {
            // 拿不到 PackageInfo 时退回 BuildConfig，不影响检查。
        }

        final String json = httpJson(FEED_URL);
        try {
            JSONObject root = new JSONObject(json);
            String software = root.optString("software", "");
            if (!software.isEmpty() && !SOFTWARE.equalsIgnoreCase(software)) {
                throw new UpdateException("这份清单属于 " + software + "，不是车览浏览器");
            }
            String packageName = root.optString("packageName",
                    root.optString("applicationId", ""));
            if (!packageName.isEmpty() && !APPLICATION_ID.equals(packageName)) {
                throw new UpdateException("清单包名与本应用不一致");
            }

            String latest = firstNonEmpty(root.optString("version", ""),
                    root.optString("versionName", ""));
            if (latest.isEmpty()) throw new UpdateException("清单缺少 version");

            JSONObject file = null;
            JSONArray files = root.optJSONArray("files");
            if (files != null && files.length() > 0) {
                file = files.optJSONObject(0);
            }
            String rawUrl = file != null
                    ? firstNonEmpty(file.optString("url", ""), root.optString("url", ""))
                    : root.optString("url", "");
            String downloadUrl = absoluteDownloadUrl(rawUrl);
            if (!isAllowedDownloadUrl(downloadUrl)) {
                throw new UpdateException("下载地址不是受信任的 HTTPS 地址");
            }

            String sha256 = file != null
                    ? firstNonEmpty(file.optString("sha256", ""), root.optString("sha256", ""))
                    : root.optString("sha256", "");
            if (!sha256.matches("(?i)[0-9a-f]{64}")) {
                throw new UpdateException("清单 SHA-256 格式无效");
            }
            long size = file != null ? file.optLong("size", 0L) : 0L;
            if (size <= 0L) size = root.optLong("size", 0L);
            int latestCode = root.optInt("versionCode", 0);
            if (latestCode <= 0 && file != null) latestCode = file.optInt("versionCode", 0);
            String fileName = file != null
                    ? firstNonEmpty(file.optString("name", ""), fileNameFromUrl(downloadUrl))
                    : fileNameFromUrl(downloadUrl);

            return new Result(current, currentCode, latest.trim(), latestCode,
                    root.optString("releasedAt", "").trim(), fileName, size,
                    sha256.trim(), downloadUrl);
        } catch (UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new UpdateException("清单格式无法解析", e);
        }
    }

    private static String httpJson(String urlText) throws UpdateException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent", "CheLanBrowser-Update/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                if (code == 404) throw new UpdateException("发布源尚未上线（HTTP 404）");
                throw new UpdateException("发布清单暂不可用（HTTP " + code + "）");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > MAX_FEED_BYTES) throw new UpdateException("发布清单超出大小上限");
                sb.append(line).append('\n');
            }
            reader.close();
            String body = sb.toString().trim();
            if (!body.startsWith("{")) throw new UpdateException("发布源返回的不是 JSON");
            return body;
        } catch (UpdateException e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new UpdateException("连接超时，请检查车机网络后重试", e);
        } catch (java.net.UnknownHostException e) {
            throw new UpdateException("找不到发布源，请检查网络后重试", e);
        } catch (Exception e) {
            throw new UpdateException("无法连接发布源：" + safeReason(e), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String absoluteDownloadUrl(String rawUrl) throws UpdateException {
        String s = rawUrl == null ? "" : rawUrl.trim();
        if (s.isEmpty()) throw new UpdateException("清单没有 APK 下载地址");
        if (s.startsWith("/")) return "https://" + TRUSTED_HOST + s;
        return s;
    }

    /** 下载域名白名单：不许清单把用户带去任意站点。 */
    public static boolean isAllowedDownloadUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URL u = new URL(value);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;
            if (u.getUserInfo() != null && !u.getUserInfo().isEmpty()) return false;
            String host = u.getHost();
            return TRUSTED_HOST.equalsIgnoreCase(host)
                    || "github.com".equalsIgnoreCase(host)
                    || "objects.githubusercontent.com".equalsIgnoreCase(host)
                    || "raw.githubusercontent.com".equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** 去 v、按 [.-] 分段；同段都是数字时按数值比，否则忽略大小写按字典比。 */
    public static int compareVersions(String left, String right) {
        String[] a = normalizeVersion(left).split("[.\\-]");
        String[] b = normalizeVersion(right).split("[.\\-]");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            String x = i < a.length ? a[i] : "";
            String y = i < b.length ? b[i] : "";
            Long xn = parseLong(x);
            Long yn = parseLong(y);
            int cmp = (xn != null && yn != null)
                    ? Long.compare(xn, yn)
                    : x.compareToIgnoreCase(y);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static String normalizeVersion(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    private static Long parseLong(String s) {
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.trim().isEmpty() ? (b == null ? "" : b.trim()) : a.trim();
    }

    private static String fileNameFromUrl(String url) {
        int slash = url == null ? -1 : url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) return "update.apk";
        String name = url.substring(slash + 1);
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        return name.isEmpty() ? "update.apk" : name;
    }

    private static String safeReason(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.trim().isEmpty()) return t.getClass().getSimpleName();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    public static String prettySize(long bytes) {
        if (bytes <= 0L) return "大小未提供";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
