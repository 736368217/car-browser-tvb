package com.carweb.audit;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Real touch/key input; the property trace observes views without setting animation state. */
final class MotionAudit {
    private static final int WINDOW_MS = 410;
    private final Instrumentation audit;
    private final Activity activity;
    private final File out;
    private final boolean baseline;
    private final boolean enabled;
    private final View sheet, panel, track, toolbar, handle, host, column, menu;
    private final ViewGroup pages;
    private final Field route;
    private final JSONArray checks = new JSONArray();
    private final JSONObject traces = new JSONObject();
    private int failed;

    static JSONObject run(Instrumentation audit, Activity activity, File out, String profile) throws Exception {
        MotionAudit test = new MotionAudit(audit, activity, out, profile);
        JSONObject result = new JSONObject().put("profile", profile).put("frameWindowMs", WINDOW_MS)
                .put("checks", test.checks).put("traces", test.traces);
        try {
            test.scenarios(result);
        } catch (Exception | AssertionError e) {
            test.check("scenario-completed", false);
            result.put("error", e.toString());
        }
        return result.put("failed", test.failed).put("pass", test.failed == 0);
    }

    private MotionAudit(Instrumentation audit, Activity activity, File out, String profile) throws Exception {
        this.audit = audit;
        this.activity = activity;
        this.out = out;
        if (!Set.of("baseline", "animated", "disabled").contains(profile)) {
            throw new IllegalArgumentException("Unknown motion profile: " + profile);
        }
        baseline = "baseline".equals(profile);
        enabled = !"disabled".equals(profile);
        sheet = view("sheetLayer");
        panel = view("sheetPanel");
        track = view("sheetTrack");
        toolbar = view("toolbar");
        column = view("column");
        handle = view("toolbarHandle");
        host = view("host");
        pages = (ViewGroup) view("pageLayer");
        route = field("currentPageRoute");
        menu = main(() -> requireControl("菜单"));
    }

    private void scenarios(JSONObject report) throws Exception {
        check("animator-scale-matches-profile", main(ValueAnimator::areAnimatorsEnabled) == enabled);
        JSONArray open = trace("sheet-open", () -> tap("菜单"), true);
        double rise = maxAbs(open, "panelY");
        double sheetOpacity = minVisible(open, "panelA", "sheetVisible");
        check("menu-opens-and-settles", main(() -> sheet.isShown() && neutral(panel)
                && find(sheet, "新建标签") != null));
        check("menu-entry-profile", baseline ? rise > 100 : enabled
                ? rise > 1 && rise <= 55 && sheetOpacity < 0.99 : rise < 0.5 && sheetOpacity >= 0.99);

        double pageWidth = main(() -> (double) ((ViewGroup) track).getChildAt(0).getWidth());
        JSONArray flip = trace("sheet-flip", () -> tap("下一页"), false);
        boolean movedBetweenPages = false;
        for (int i = 0; i < flip.length(); i++) {
            double x = flip.getJSONObject(i).getDouble("trackX");
            movedBetweenPages |= x < -1 && x > 1 - pageWidth;
        }
        check("menu-page-two-and-flip-profile", movedBetweenPages == enabled
                && main(() -> Math.abs(track.getTranslationX() + pageWidth) < 1
                        && find(sheet, "页内查找") != null));
        tap("上一页");
        SystemClock.sleep(240);
        check("menu-can-return-to-page-one", main(() -> Math.abs(track.getTranslationX()) < 1
                && find(sheet, "新建标签") != null));
        Rect arrow = bounds("下一页");
        tap(arrow, 20);
        tap(arrow, 20);
        SystemClock.sleep(260);
        check("rapid-arrow-taps-settle-on-page-one", main(() -> Math.abs(track.getTranslationX()) < 1
                && find(sheet, "新建标签") != null));

        JSONArray close = trace("sheet-close", () -> tap("关闭功能页"), false);
        check("menu-dismisses-and-restores-toolbar", main(this::onHome)
                && (enabled ? maxAbs(close, "panelY") > 1 : maxAbs(close, "panelY") < 0.5));
        tap("菜单");
        back();
        back();
        SystemClock.sleep(240);
        check("rapid-menu-back-leaves-no-overlay", main(this::onHome));

        tap("菜单");
        SystemClock.sleep(245);
        JSONArray enter = trace("page-enter", () -> tap("设置"), false);
        check("settings-opens-and-settles", main(() -> current("设置") && neutral(pages.getChildAt(0))));
        check("page-entry-profile", baseline ? maxAbs(enter, "pageX") > 1 : enabled
                ? maxAbs(enter, "pageX") > 1 && maxAbs(enter, "pageX") <= 42
                    && minVisible(enter, "pageA", "pageVisible") >= 0.87
                : maxAbs(enter, "pageX") < 0.5 && minVisible(enter, "pageA", "pageVisible") >= 0.99);
        View parent = main(() -> pages.getChildAt(0));
        swipeSettings();
        int parentY = main(this::scrollY);
        check("settings-scrolls-before-opening-child", parentY > 0);
        JSONArray child = trace("page-child", () -> tap("关于车览浏览器"), true);
        check("child-opens-and-settles", main(() -> current("关于") && neutral(pages.getChildAt(0))));
        if (!baseline) check("child-starts-at-top", main(this::scrollY) == 0);
        JSONArray returning = trace("page-back", () -> tap("返回"), true);
        int restoredY = main(this::scrollY);
        report.put("parentScrollBeforePx", parentY).put("parentScrollAfterPx", restoredY);
        check("parent-frame-and-scroll-restored", main(() -> current("设置")
                && pages.getChildAt(0) == parent && neutral(parent)) && restoredY == parentY);
        double minBackAlpha = minVisible(returning, "pageA", "pageVisible");
        check("parent-return-profile", baseline || (enabled
                ? min(returning, "pageX") < -1 && maxAbs(returning, "pageX") <= 42 && minBackAlpha >= 0.87
                : maxAbs(returning, "pageX") < 0.5 && minBackAlpha >= 0.99));
        tap("关于车览浏览器");
        back();
        SystemClock.sleep(350);
        check("back-during-child-entry-restores-parent", main(() -> current("设置") && scrollY() == parentY));
        back();
        back();
        SystemClock.sleep(245);
        check("rapid-page-back-returns-to-live-home", main(this::onHome));
        report.put("childTravelPx", maxAbs(child, "pageX"));

        tap("菜单");
        SystemClock.sleep(245);
        JSONArray full = trace("fullscreen-enter", () -> tap("全屏"), true);
        check("fullscreen-has-clickable-exit", main(() -> handle.isShown() && handle.isClickable()
                && neutral(handle) && !toolbar.isShown() && host.getTop() == 0));
        JSONArray restore = trace("fullscreen-restore", () -> tap("退出全屏"), true);
        check("fullscreen-restores-live-toolbar", main(() -> onHome() && neutral(toolbar)
                && !handle.isShown() && host.getTop() == toolbar.getHeight()));
        check("fullscreen-profile", baseline || (enabled
                ? minVisible(full, "handleA", "handleVisible") < 0.99 && min(restore, "toolbarY") < -1
                : minVisible(full, "handleA", "handleVisible") >= 0.99 && maxAbs(restore, "toolbarY") < 0.5));
        check("fullscreen-layout-only-at-boundary", distinct(full, "hostTop") == 2
                && distinct(restore, "hostTop") == 2);

        Rect b = bounds("菜单");
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY());
        SystemClock.sleep(100);
        double scale = main(() -> (double) menu.getScaleX());
        double alpha = main(() -> (double) menu.getAlpha());
        JSONArray cancel = trace("press-cancel", () -> touch(down, MotionEvent.ACTION_CANCEL,
                b.centerX(), b.centerY()), false);
        check("press-feedback-and-cancel", alpha < 0.99 && (enabled ? scale < 0.999 : scale == 1)
                && main(() -> onHome() && neutral(menu)));
        boolean releaseFrame = false;
        for (int i = 0; i < cancel.length(); i++) {
            double a = cancel.getJSONObject(i).getDouble("menuA");
            releaseFrame |= a > alpha + 0.003 && a < 0.997;
        }
        check("press-release-profile", releaseFrame == enabled);
        report.put("menuRisePx", rise).put("minParentReturnAlpha", minBackAlpha)
                .put("fullscreenRestoreTravelPx", maxAbs(restore, "toolbarY"))
                .put("pressedScale", scale).put("pressedAlpha", alpha);
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }

    private JSONArray trace(String name, Action action, boolean image) throws Exception {
        JSONArray frames = new JSONArray();
        long start = SystemClock.uptimeMillis();
        Choreographer.FrameCallback cb = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                try { frames.put(sample(SystemClock.uptimeMillis() - start)); }
                catch (Exception e) { throw new IllegalStateException(e); }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        main(() -> { frames.put(sample(0)); Choreographer.getInstance().postFrameCallback(cb); return null; });
        Bitmap bitmap = null;
        JSONArray captureMs = null;
        try {
            action.run();
            if (image && enabled) {
                SystemClock.sleep(46);
                long begin = SystemClock.uptimeMillis() - start;
                bitmap = audit.getUiAutomation().takeScreenshot();
                captureMs = new JSONArray().put(begin).put(SystemClock.uptimeMillis() - start);
            }
            SystemClock.sleep(Math.max(0, start + WINDOW_MS - SystemClock.uptimeMillis()));
        } finally {
            main(() -> { Choreographer.getInstance().removeFrameCallback(cb);
                frames.put(sample(SystemClock.uptimeMillis() - start)); return null; });
        }
        if (frames.length() < 5) throw new AssertionError("Too few view samples: " + name);
        JSONObject trace = new JSONObject().put("frames", frames);
        traces.put(name, trace);
        if (bitmap != null) {
            String fileName = "motion-" + name + ".png";
            try (FileOutputStream stream = new FileOutputStream(new File(out, fileName))) {
                if (bitmap.getWidth() != 2880 || bitmap.getHeight() != 1620
                        || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new AssertionError("Frame capture failed: " + name);
                }
            } finally { bitmap.recycle(); }
            trace.put("screenshot", fileName).put("screenshotWindowMs", captureMs);
        }
        return frames;
    }

    private JSONObject sample(long ms) throws Exception {
        View active = pages.getChildAt(0);
        return new JSONObject().put("ms", ms).put("sheetVisible", sheet.isShown())
                .put("panelY", panel.getTranslationY()).put("panelA", panel.getAlpha())
                .put("trackX", track.getTranslationX()).put("pageVisible", pages.isShown())
                .put("route", route.get(activity))
                .put("pageX", pages.getTranslationX() + (active == null ? 0 : active.getTranslationX()))
                .put("pageA", pages.getAlpha() * (active == null ? 1 : active.getAlpha()))
                .put("handleVisible", handle.isShown()).put("handleA", handle.getAlpha())
                .put("toolbarY", toolbar.getTranslationY()).put("hostTop", host.getTop())
                .put("menuA", menu.getAlpha());
    }

    private void check(String name, boolean pass) throws Exception {
        checks.put(new JSONObject().put("name", name).put("pass", pass));
        if (!pass) failed++;
    }

    private static double maxAbs(JSONArray a, String field) throws Exception {
        double max = 0;
        for (int i = 0; i < a.length(); i++) max = Math.max(max, Math.abs(a.getJSONObject(i).getDouble(field)));
        return max;
    }

    private static double min(JSONArray a, String field) throws Exception {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < a.length(); i++) min = Math.min(min, a.getJSONObject(i).getDouble(field));
        return min;
    }

    private static double minVisible(JSONArray a, String value, String visibility) throws Exception {
        double min = 1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject f = a.getJSONObject(i);
            if (f.getBoolean(visibility)) min = Math.min(min, f.getDouble(value));
        }
        return min;
    }

    private static int distinct(JSONArray a, String field) throws Exception {
        Set<Integer> sizes = new HashSet<>();
        for (int i = 0; i < a.length(); i++) sizes.add(a.getJSONObject(i).getInt(field));
        return sizes.size();
    }

    private static boolean neutral(View v) {
        return v != null && v.getAlpha() == 1f && v.getScaleX() == 1f && v.getScaleY() == 1f
                && v.getTranslationX() == 0f && v.getTranslationY() == 0f;
    }

    private boolean current(String title) throws Exception {
        return pages.isShown() && title.equals(route.get(activity));
    }

    private int scrollY() {
        ViewGroup frame = (ViewGroup) pages.getChildAt(0);
        return frame == null ? -1 : frame.getChildAt(1).getScrollY();
    }

    private boolean onHome() {
        return !sheet.isShown() && !pages.isShown() && column.isShown() && toolbar.isShown() && menu.isShown();
    }

    private Field field(String name) throws Exception {
        Field f = activity.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private View view(String name) throws Exception { return (View) field(name).get(activity); }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        audit.runOnMainSync(task);
        return task.get();
    }

    private static View find(View v, String label) {
        if (!v.isShown() || !v.getGlobalVisibleRect(new Rect())) return null;
        if (label.contentEquals(v.getContentDescription() != null ? v.getContentDescription() : "")
                || v instanceof TextView && label.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = find(g.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View requireControl(String label) {
        View v = find(activity.getWindow().getDecorView(), label);
        if (v == null) throw new AssertionError("Control missing: " + label);
        return v;
    }

    private Rect bounds(String label) throws Exception {
        return main(() -> {
            View v = requireControl(label);
            Rect b = new Rect();
            if (!v.getGlobalVisibleRect(b)) throw new AssertionError("Control clipped: " + label);
            return b;
        });
    }

    private void tap(String label) throws Exception { tap(bounds(label), 34); }

    private void tap(Rect b, long holdMs) {
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY());
        SystemClock.sleep(holdMs);
        touch(down, MotionEvent.ACTION_UP, b.centerX(), b.centerY());
    }

    private void swipeSettings() {
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, 2100, 1450);
        for (int i = 1; i <= 20; i++) {
            SystemClock.sleep(25);
            touch(down, MotionEvent.ACTION_MOVE, 2100, 1450 - 57 * i);
        }
        touch(down, MotionEvent.ACTION_UP, 2100, 310);
        SystemClock.sleep(350);
    }

    private void back() { audit.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); }

    private void touch(long down, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!audit.getUiAutomation().injectInputEvent(event, true)) {
                throw new AssertionError("Touch injection refused: " + action);
            }
        } finally { event.recycle(); }
    }
}
