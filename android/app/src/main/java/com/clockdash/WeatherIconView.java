package com.clockdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 天气图标（纯 Canvas 自绘，无图片资源，老系统兼容）。
 * 数据源（高德/Open-Meteo）只给文字描述，按文字映射图标类型。
 */
public class WeatherIconView extends View {

    public static final int T_NONE = 0;      // 不绘制
    public static final int T_SUN = 1;       // 晴
    public static final int T_CLOUD_SUN = 2; // 多云
    public static final int T_OVERCAST = 3;  // 阴
    public static final int T_RAIN = 4;      // 雨（含阵雨/毛毛雨）
    public static final int T_THUNDER = 5;   // 雷阵雨
    public static final int T_SNOW = 6;      // 雪（含阵雪）
    public static final int T_SLEET = 7;     // 雨夹雪/冰雹
    public static final int T_FOG = 8;       // 雾/霾/沙尘

    private static final int C_SUN = 0xFFF2B23E;     // amber
    private static final int C_CLOUD = 0xFFB9C6D2;   // 亮云
    private static final int C_CLOUD_DIM = 0xFF7C8B99; // 暗云
    private static final int C_RAIN = 0xFF4EA3FF;    // accent
    private static final int C_SNOW = 0xFFEDF1F5;    // fg
    private static final int C_BOLT = 0xFFF2B23E;    // 闪电

    private int mType = T_NONE;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    public WeatherIconView(Context context) {
        super(context);
    }

    public WeatherIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 按天气文字映射图标类型（兼容高德与 Open-Meteo 两种文案） */
    public static int typeFor(String text) {
        if (text == null) return T_NONE;
        String s = text.trim();
        if (s.length() == 0 || "—".equals(s)) return T_NONE;
        if (s.contains("雷")) return T_THUNDER;
        if (s.contains("雨夹雪") || s.contains("冰雹")) return T_SLEET;
        if (s.contains("雪")) return T_SNOW;
        if (s.contains("雾") || s.contains("霾")
                || s.contains("沙") || s.contains("尘")) return T_FOG;
        if (s.contains("雨")) return T_RAIN;
        if (s.contains("阴")) return T_OVERCAST;
        if (s.contains("云")) return T_CLOUD_SUN;
        if (s.contains("晴")) return T_SUN;
        return T_NONE;
    }

    public void setType(int type) {
        if (mType != type) {
            mType = type;
            invalidate();
        }
    }

    public int getType() {
        return mType;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mType == T_NONE) return;
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;
        switch (mType) {
            case T_SUN: drawSun(canvas, s, 0.5f, 0.5f, 0.26f); break;
            case T_CLOUD_SUN:
                drawSun(canvas, s, 0.66f, 0.32f, 0.15f);
                drawCloud(canvas, s, 0.0f, 0.10f, C_CLOUD);
                break;
            case T_OVERCAST:
                drawCloud(canvas, s, 0.10f, -0.06f, C_CLOUD_DIM);
                drawCloud(canvas, s, -0.06f, 0.10f, C_CLOUD);
                break;
            case T_RAIN:
                drawCloud(canvas, s, 0f, -0.08f, C_CLOUD);
                strokeStyle(C_RAIN, s * 0.055f);
                float x1 = 0.36f * s, x2 = 0.50f * s, x3 = 0.64f * s;
                float dy = s * 0.13f, dx = -s * 0.045f;
                canvas.drawLine(x1, 0.66f * s, x1 + dx, 0.66f * s + dy, mPaint);
                canvas.drawLine(x2, 0.66f * s, x2 + dx, 0.66f * s + dy, mPaint);
                canvas.drawLine(x3, 0.66f * s, x3 + dx, 0.66f * s + dy, mPaint);
                break;
            case T_THUNDER:
                drawCloud(canvas, s, 0f, -0.10f, C_CLOUD_DIM);
                fillStyle(C_BOLT);
                mPath.reset();
                mPath.moveTo(0.54f * s, 0.56f * s);
                mPath.lineTo(0.40f * s, 0.74f * s);
                mPath.lineTo(0.49f * s, 0.74f * s);
                mPath.lineTo(0.42f * s, 0.92f * s);
                mPath.lineTo(0.62f * s, 0.68f * s);
                mPath.lineTo(0.52f * s, 0.68f * s);
                mPath.lineTo(0.60f * s, 0.56f * s);
                mPath.close();
                canvas.drawPath(mPath, mPaint);
                break;
            case T_SNOW:
                drawCloud(canvas, s, 0f, -0.08f, C_CLOUD);
                fillStyle(C_SNOW);
                canvas.drawCircle(0.38f * s, 0.74f * s, s * 0.045f, mPaint);
                canvas.drawCircle(0.52f * s, 0.84f * s, s * 0.045f, mPaint);
                canvas.drawCircle(0.66f * s, 0.74f * s, s * 0.045f, mPaint);
                break;
            case T_SLEET:
                drawCloud(canvas, s, 0f, -0.08f, C_CLOUD);
                strokeStyle(C_RAIN, s * 0.055f);
                canvas.drawLine(0.40f * s, 0.66f * s, 0.35f * s, 0.80f * s, mPaint);
                fillStyle(C_SNOW);
                canvas.drawCircle(0.60f * s, 0.76f * s, s * 0.05f, mPaint);
                break;
            case T_FOG:
                strokeStyle(C_CLOUD_DIM, s * 0.07f);
                canvas.drawLine(0.18f * s, 0.36f * s, 0.82f * s, 0.36f * s, mPaint);
                canvas.drawLine(0.28f * s, 0.52f * s, 0.92f * s, 0.52f * s, mPaint);
                canvas.drawLine(0.14f * s, 0.68f * s, 0.70f * s, 0.68f * s, mPaint);
                canvas.drawLine(0.38f * s, 0.84f * s, 0.86f * s, 0.84f * s, mPaint);
                break;
            default:
                break;
        }
    }

    /** 太阳：圆 + 8 条光芒 */
    private void drawSun(Canvas c, float s, float cx, float cy, float r) {
        float px = cx * s, py = cy * s, pr = r * s;
        fillStyle(C_SUN);
        c.drawCircle(px, py, pr, mPaint);
        strokeStyle(C_SUN, s * 0.05f);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float x1 = px + (float) Math.cos(a) * pr * 1.35f;
            float y1 = py + (float) Math.sin(a) * pr * 1.35f;
            float x2 = px + (float) Math.cos(a) * pr * 1.75f;
            float y2 = py + (float) Math.sin(a) * pr * 1.75f;
            c.drawLine(x1, y1, x2, y2, mPaint);
        }
    }

    /** 云朵：三个圆 + 底部圆角矩形拼成，ox/oy 为整体偏移（相对边长） */
    private void drawCloud(Canvas c, float s, float ox, float oy, int color) {
        fillStyle(color);
        c.drawCircle((0.36f + ox) * s, (0.56f + oy) * s, 0.15f * s, mPaint);
        c.drawCircle((0.54f + ox) * s, (0.47f + oy) * s, 0.19f * s, mPaint);
        c.drawCircle((0.69f + ox) * s, (0.58f + oy) * s, 0.14f * s, mPaint);
        mRect.set((0.30f + ox) * s, (0.56f + oy) * s, (0.76f + ox) * s, (0.72f + oy) * s);
        c.drawRoundRect(mRect, 0.08f * s, 0.08f * s, mPaint);
    }

    private void fillStyle(int color) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
    }

    private void strokeStyle(int color, float w) {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(w);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setColor(color);
    }
}
