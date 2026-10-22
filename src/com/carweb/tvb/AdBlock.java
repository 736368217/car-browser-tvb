package com.carweb.tvb;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轻量广告过滤。
 *
 * 车机上没有扩展生态，只能做「域名 + 路径」这一层：拦掉常见的广告/统计域名，
 * 以及 /ads/、/pagead/、/gtag/js 这类一眼就是广告的路径。规则刻意保守 ——
 * 宁可漏，也不要拦掉页面自己的 js/css 把站弄坏。
 */
public final class AdBlock {

    private static final String[] HOST_SUFFIXES = {
            // 广告投放 / 交易平台
            "doubleclick.net", "googlesyndication.com", "adservice.google.com",
            "tanx.com", "alimama.com", "cpro.baidu.com", "pos.baidu.com",
            "adnxs.com", "appnexus.com", "criteo.com", "taboola.com", "outbrain.com",
            "moatads.com", "adsrvr.org", "adcolony.com", "applovin.com", "chartboost.com",
            // 统计 / 埋点
            "google-analytics.com", "googletagmanager.com", "hm.baidu.com",
            "mmstat.com", "umeng.com", "umeng.co", "cnzz.com", "growingio.com",
            "mixpanel.com", "amplitude.com", "scorecardresearch.com", "quantserve.com"
    };

    private static final String[] PATH_PARTS = {
            "/ads/", "/adserver", "/pagead/", "/gtag/js", "/analytics.js", "/adframe"
    };

    private static volatile boolean enabled = true;
    private static final AtomicInteger blocked = new AtomicInteger();

    private AdBlock() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int blockedCount() {
        return blocked.get();
    }

    public static void resetCount() {
        blocked.set(0);
    }

    /** 这个请求该不该被掐掉 */
    public static boolean shouldBlock(String url) {
        if (!enabled || url == null) return false;
        String u = url.toLowerCase();
        if (!u.startsWith("http")) return false;
        int scheme = u.indexOf("://");
        if (scheme < 0) return false;
        String rest = u.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);

        for (String s : HOST_SUFFIXES) {
            if (host.equals(s) || host.endsWith("." + s)) {
                blocked.incrementAndGet();
                return true;
            }
        }
        String path = slash < 0 ? "/" : rest.substring(slash);
        for (String p : PATH_PARTS) {
            if (path.contains(p)) {
                blocked.incrementAndGet();
                return true;
            }
        }
        return false;
    }
}
