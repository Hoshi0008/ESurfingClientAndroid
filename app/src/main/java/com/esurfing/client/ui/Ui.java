package com.esurfing.client.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** 小工具：dp/sp 换算、圆角/渐变背景、文字与图标的快速构造。 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static float dpf(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int sp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** 纯色圆角背景，可选描边。 */
    public static GradientDrawable round(Context c, int fill, float radiusDp,
                                         int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dpf(c, radiusDp));
        if (strokeDp > 0) {
            d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        }
        return d;
    }

    /** 对角渐变圆角背景。 */
    public static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{from, to});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dpf(c, radiusDp));
        return d;
    }

    public static int alpha(int color, float a) {
        int al = Math.round(255 * Math.max(0f, Math.min(1f, a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    public static TextView text(Context c, CharSequence s, float sizeSp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        v.setTextColor(color);
        if (bold) {
            v.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return v;
    }

    public static TextView mono(Context c, CharSequence s, float sizeSp, int color, boolean bold) {
        TextView v = text(c, s, sizeSp, color, bold);
        v.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    public static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        Drawable d = c.getResources().getDrawable(res, c.getTheme());
        if (d != null) {
            d = d.mutate();
            d.setTint(color);
        }
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    public static View spacer(Context c, float hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, hDp)));
        return v;
    }

    public static View flexSpacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return v;
    }

    public static LinearLayout.LayoutParams lpH(float heightDp, Context c) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp));
    }

    /** 把秒格式化成 hh:mm:ss（服务启动时间）。 */
    public static String hms(long epochMillis) {        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(epochMillis);
        return String.format(Locale.US, "%02d:%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
    }

    /** 人类可读的字节数。 */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** 失败/异常类的状态文案（用红色显示）。 */
    public static boolean isNegativePhase(String phase) {
        if (phase == null) {
            return false;
        }
        return phase.contains("失败") || phase.contains("被拒") || phase.contains("异常")
                || phase.contains("失效") || phase.contains("错误") || phase.contains("不可用")
                || phase.contains("缺少") || phase.contains("超时") || phase.contains("未配置")
                || phase.contains("不在天翼网段") || phase.contains("需要门户认证")
                || phase.contains("需要 eIA");
    }

    /** 正常状态文案（用绿色显示）。 */
    public static boolean isPositivePhase(String phase) {
        if (phase == null) {
            return false;
        }
        return phase.contains("已在线") || phase.contains("网络已在线")
                || phase.contains("运行中");
    }

    /** 状态文案 → 颜色：负面红色、正常绿色、进行中/等待用强调色。 */
    public static int phaseColor(Theme t, String phase) {
        if (phase == null || phase.isEmpty()) {
            return t.green;
        }
        if (isNegativePhase(phase)) {
            return t.red;
        }
        if (isPositivePhase(phase)) {
            return t.green;
        }
        return t.accent1;
    }

    /** 按触摸状态给按钮做轻微缩放，点击更有质感。 */
    public static void pressFeedback(final View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
