package com.carweb.tvb;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 工具条图标（v2 重绘）。
 *
 * 画法：所有路径都用 **24×24 的标准视框坐标**写，draw() 里整体缩放到位，
 * 这样每条线、每个圆角都是设计时看到的比例，不用为不同尺寸另算一套数。
 *
 * 规范：
 *   · 描边 2.0（相对 24 视框），圆头端点 + 圆角连接 —— 视觉重量一致
 *   · 图标内容控制在 4..20 的方框内，四周留 4 的呼吸
 *   · 不用系统 drawable（那套是 hdpi 位图，放大就糊），也不用 Unicode 字形（换字体变方块）
 */
public class GlyphIcon extends Drawable {

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

    /** 24 视框里的标准描边宽度 */
    private static final float STROKE = 2.0f;

    private final int type;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    public GlyphIcon(int type, int color) {
        this.type = type;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    public void setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        final float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.exactCenterX() - 12f * s, b.exactCenterY() - 12f * s);
        c.scale(s, s);

        stroke.setStrokeWidth(STROKE);

        switch (type) {
            case BACK:
                path.reset();
                path.moveTo(15f, 5f);
                path.lineTo(8f, 12f);
                path.lineTo(15f, 19f);
                c.drawPath(path, stroke);
                break;

            case FORWARD:
                path.reset();
                path.moveTo(9f, 5f);
                path.lineTo(16f, 12f);
                path.lineTo(9f, 19f);
                c.drawPath(path, stroke);
                break;

            case REFRESH: {
                // 280° 圆弧 + 开口处一个实心小三角
                oval.set(4.2f, 4.2f, 19.8f, 19.8f);
                c.drawArc(oval, -55f, 280f, false, stroke);
                final double a = Math.toRadians(-55.0);
                final float ax = (float) (12f + 7.8f * Math.cos(a));
                final float ay = (float) (12f + 7.8f * Math.sin(a));
                path.reset();
                path.moveTo(ax + 0.4f, ay - 3.2f);
                path.lineTo(ax + 3.4f, ay + 0.6f);
                path.lineTo(ax - 1.6f, ay + 1.8f);
                path.close();
                c.drawPath(path, fill);
                break;
            }

            case HOME:
                // 屋顶
                path.reset();
                path.moveTo(3.8f, 11.2f);
                path.lineTo(12f, 4.2f);
                path.lineTo(20.2f, 11.2f);
                c.drawPath(path, stroke);
                // 屋身 + 门
                path.reset();
                path.moveTo(6.4f, 9.6f);
                path.lineTo(6.4f, 19.6f);
                path.lineTo(17.6f, 19.6f);
                path.lineTo(17.6f, 9.6f);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(10.2f, 19.6f);
                path.lineTo(10.2f, 14.6f);
                path.lineTo(13.8f, 14.6f);
                path.lineTo(13.8f, 19.6f);
                c.drawPath(path, stroke);
                break;

            case STAR:
                c.drawPath(starPath(12f, 12.2f, 8.4f, 3.6f), stroke);
                break;

            case MENU:
                path.reset();
                path.moveTo(4f, 7f);
                path.lineTo(20f, 7f);
                path.moveTo(4f, 12f);
                path.lineTo(20f, 12f);
                path.moveTo(4f, 17f);
                path.lineTo(20f, 17f);
                c.drawPath(path, stroke);
                break;

            case TABS:
                // 后层（只画左上两条边，暗示被前面那层挡住）
                path.reset();
                path.moveTo(8f, 4.6f);
                path.lineTo(19.4f, 4.6f);
                path.lineTo(19.4f, 15.2f);
                c.drawPath(path, stroke);
                // 前层
                path.reset();
                path.moveTo(4.6f, 8.4f);
                path.lineTo(4.6f, 19.4f);
                path.lineTo(15.6f, 19.4f);
                path.lineTo(15.6f, 8.4f);
                path.close();
                c.drawPath(path, stroke);
                break;

            case DOWN:
                path.reset();
                path.moveTo(12f, 3.8f);
                path.lineTo(12f, 15.4f);
                path.moveTo(6.6f, 10.2f);
                path.lineTo(12f, 15.6f);
                path.lineTo(17.4f, 10.2f);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(5f, 19.6f);
                path.lineTo(19f, 19.6f);
                c.drawPath(path, stroke);
                break;

            case SEARCH:
                oval.set(4.4f, 4.4f, 15.6f, 15.6f);
                c.drawOval(oval, stroke);
                path.reset();
                path.moveTo(15.2f, 15.2f);
                path.lineTo(20f, 20f);
                c.drawPath(path, stroke);
                break;

            case CLOSE:
                path.reset();
                path.moveTo(6.4f, 6.4f);
                path.lineTo(17.6f, 17.6f);
                path.moveTo(17.6f, 6.4f);
                path.lineTo(6.4f, 17.6f);
                c.drawPath(path, stroke);
                break;

            case CLOCK:
                oval.set(3.6f, 3.6f, 20.4f, 20.4f);
                c.drawOval(oval, stroke);
                path.reset();
                path.moveTo(12f, 7.4f);
                path.lineTo(12f, 12.2f);
                path.lineTo(15.6f, 14.4f);
                c.drawPath(path, stroke);
                break;

            case SHARE:
                path.reset();
                path.moveTo(8.2f, 10.8f);
                path.lineTo(15.8f, 7.2f);
                path.moveTo(8.2f, 13.2f);
                path.lineTo(15.8f, 16.8f);
                c.drawPath(path, stroke);
                c.drawCircle(6f, 12f, 2.3f, fill);
                c.drawCircle(18f, 6f, 2.3f, fill);
                c.drawCircle(18f, 18f, 2.3f, fill);
                break;

            case DESKTOP:
                path.reset();
                path.moveTo(5f, 4.6f);
                path.lineTo(19f, 4.6f);
                path.lineTo(19f, 15.4f);
                path.lineTo(5f, 15.4f);
                path.close();
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(12f, 15.4f);
                path.lineTo(12f, 19.4f);
                path.moveTo(8.2f, 19.4f);
                path.lineTo(15.8f, 19.4f);
                c.drawPath(path, stroke);
                break;

            case MOON:
                path.reset();
                path.moveTo(20.2f, 14.6f);
                path.cubicTo(19.0f, 17.6f, 16.2f, 19.6f, 12.9f, 19.6f);
                path.cubicTo(8.5f, 19.6f, 4.9f, 16.0f, 4.9f, 11.6f);
                path.cubicTo(4.9f, 8.3f, 6.9f, 5.5f, 9.9f, 4.3f);
                path.cubicTo(8.4f, 6.2f, 7.6f, 8.5f, 7.6f, 11.0f);
                path.cubicTo(7.6f, 15.4f, 11.2f, 19.0f, 15.6f, 19.0f);
                path.cubicTo(17.3f, 19.0f, 18.9f, 18.4f, 20.2f, 17.4f);
                path.close();
                c.drawPath(path, stroke);
                break;

            case EXPAND:
                path.reset();
                path.moveTo(4.4f, 9.4f); path.lineTo(4.4f, 4.4f); path.lineTo(9.4f, 4.4f);
                path.moveTo(14.6f, 4.4f); path.lineTo(19.6f, 4.4f); path.lineTo(19.6f, 9.4f);
                path.moveTo(19.6f, 14.6f); path.lineTo(19.6f, 19.6f); path.lineTo(14.6f, 19.6f);
                path.moveTo(9.4f, 19.6f); path.lineTo(4.4f, 19.6f); path.lineTo(4.4f, 14.6f);
                c.drawPath(path, stroke);
                break;

            case TRASH:
                path.reset();
                path.moveTo(3.8f, 6.6f); path.lineTo(20.2f, 6.6f);
                path.moveTo(9.2f, 3.8f); path.lineTo(14.8f, 3.8f);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(6.2f, 6.6f);
                path.lineTo(6.2f, 19.4f);
                path.lineTo(17.8f, 19.4f);
                path.lineTo(17.8f, 6.6f);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(10.2f, 10.2f); path.lineTo(10.2f, 16.2f);
                path.moveTo(13.8f, 10.2f); path.lineTo(13.8f, 16.2f);
                c.drawPath(path, stroke);
                break;

            case POWER:
                path.reset();
                path.moveTo(12f, 3.6f);
                path.lineTo(12f, 11.2f);
                c.drawPath(path, stroke);
                oval.set(4.4f, 5.2f, 19.6f, 20.4f);
                c.drawArc(oval, 122f, 296f, false, stroke);
                break;

            case SLIDERS:
                path.reset();
                path.moveTo(3.8f, 7f); path.lineTo(20.2f, 7f);
                path.moveTo(3.8f, 12f); path.lineTo(20.2f, 12f);
                path.moveTo(3.8f, 17f); path.lineTo(20.2f, 17f);
                c.drawPath(path, stroke);
                c.drawCircle(9f, 7f, 2.1f, fill);
                c.drawCircle(15.6f, 12f, 2.1f, fill);
                c.drawCircle(7.6f, 17f, 2.1f, fill);
                break;

            default:
                break;
        }

        c.restore();
    }

    /** 五角星（描边用）：外半径 outer、内半径 inner */
    private Path starPath(float cx, float cy, float outer, float inner) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double ang = Math.toRadians(-90.0 + i * 36.0);
            float rad = (i % 2 == 0) ? outer : inner;
            float x = (float) (cx + rad * Math.cos(ang));
            float y = (float) (cy + rad * Math.sin(ang));
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        return path;
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        stroke.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
