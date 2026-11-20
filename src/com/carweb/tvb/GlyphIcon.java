package com.carweb.tvb;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Lucide outline icons, rendered offline using Android native VectorDrawables.
 * Upstream paths and notices: third_party/lucide; APK notices: assets/licenses/lucide.txt.
 * Keep the original IDs stable for existing toolbar/menu callers.
 */
public final class GlyphIcon extends Drawable {
    public static final int BACK = 0;
    public static final int FORWARD = 1;
    public static final int REFRESH = 2;
    public static final int HOME = 3;
    public static final int STAR = 4;
    public static final int MENU = 5;
    public static final int CLOSE = 6;
    public static final int TABS = 7;
    public static final int DOWN = 8;
    public static final int SEARCH = 9;
    public static final int CLOCK = 10;
    public static final int SHARE = 11;
    public static final int DESKTOP = 12;
    public static final int MOON = 13;
    public static final int EXPAND = 14;
    public static final int TRASH = 15;
    public static final int POWER = 16;
    public static final int SLIDERS = 17;
    public static final int SETTINGS = 18;
    public static final int NEW_TAB = 19;
    public static final int ABOUT = 20;
    public static final int READER = 21;
    public static final int NO_IMAGE = 22;
    public static final int SHIELD = 23;
    public static final int ERASER = 24;
    public static final int COLLAPSE = 25;
    public static final int CHEVRON_LEFT = 26;
    public static final int CHEVRON_RIGHT = 27;
    public static final int PLUS = 28;
    public static final int FILE = 29;
    public static final int FILE_TEXT = 30;
    public static final int FILE_IMAGE = 31;
    public static final int FILE_VIDEO = 32;
    public static final int FILE_AUDIO = 33;
    public static final int APK = 34;
    public static final int MINUS = 35;
    public static final int CHEVRON_DOWN = 36;
    public static final int CHEVRON_UP = 37;
    public static final int GLOBE = 38;
    public static final int UPDATE = 39;

    private static final int[] RESOURCES = {
        R.drawable.ic_lucide_arrow_left, R.drawable.ic_lucide_arrow_right,
        R.drawable.ic_lucide_refresh_cw, R.drawable.ic_lucide_house,
        R.drawable.ic_lucide_star, R.drawable.ic_lucide_menu,
        R.drawable.ic_lucide_x, R.drawable.ic_lucide_panels_top_left,
        R.drawable.ic_lucide_download, R.drawable.ic_lucide_search,
        R.drawable.ic_lucide_clock, R.drawable.ic_lucide_share_2,
        R.drawable.ic_lucide_monitor, R.drawable.ic_lucide_moon,
        R.drawable.ic_lucide_maximize, R.drawable.ic_lucide_trash,
        R.drawable.ic_lucide_power, R.drawable.ic_lucide_sliders_horizontal,
        R.drawable.ic_lucide_settings, R.drawable.ic_lucide_square_plus,
        R.drawable.ic_lucide_info, R.drawable.ic_lucide_book_open_text,
        R.drawable.ic_lucide_image_off, R.drawable.ic_lucide_shield_check,
        R.drawable.ic_lucide_eraser, R.drawable.ic_lucide_minimize,
        R.drawable.ic_lucide_chevron_left, R.drawable.ic_lucide_chevron_right,
        R.drawable.ic_lucide_plus, R.drawable.ic_lucide_file,
        R.drawable.ic_lucide_file_text, R.drawable.ic_lucide_image,
        R.drawable.ic_lucide_film, R.drawable.ic_lucide_file_music,
        R.drawable.ic_lucide_package, R.drawable.ic_lucide_minus,
        R.drawable.ic_lucide_chevron_down, R.drawable.ic_lucide_chevron_up,
        R.drawable.ic_lucide_globe, R.drawable.ic_lucide_circle_arrow_down
    };
    private final Drawable vector;

    public GlyphIcon(Resources resources, int type, int color) {
        if (type < 0 || type >= RESOURCES.length) throw new IllegalArgumentException("Unknown icon: " + type);
        vector = resources.getDrawable(RESOURCES[type], null).mutate();
        vector.setTint(color);
    }

    public void setColor(int color) {
        vector.setTint(color);
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) {
        int size = Math.min(bounds.width(), bounds.height());
        int left = bounds.left + (bounds.width() - size) / 2;
        int top = bounds.top + (bounds.height() - size) / 2;
        // One viewport unit keeps antialiased round caps clear of small bitmap edges.
        int inset = Math.round(size / 24f);
        vector.setBounds(left + inset, top + inset, left + size - inset, top + size - inset);
    }

    @Override public void draw(Canvas canvas) { vector.draw(canvas); }
    @Override public void setAlpha(int alpha) { vector.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter colorFilter) { vector.setColorFilter(colorFilter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
