package com.esurfing.client.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** 圆角胶囊按钮。三种样式：主渐变、黄色、纯文字。 */
public final class Pill extends TextView {

    public static final int PRIMARY = 0;
    public static final int YELLOW = 1;
    public static final int GHOST = 2;
    public static final int TONAL = 3;
    public static final int DANGER = 4;

    public Pill(Context c, Theme t, CharSequence text, int style) {
        super(c);
        setText(text);
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15.5f);
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setMinHeight(Ui.dp(c, 50));
        setPadding(Ui.dp(c, 28), 0, Ui.dp(c, 28), 0);
        setClickable(true);
        setFocusable(true);
        apply(t, style);
        Ui.pressFeedback(this);
    }

    public void apply(Theme t, int style) {
        switch (style) {
            case YELLOW:
                setTextColor(t.onYellow);
                setBackground(Ui.gradient(getContext(), 0xFFFFE07A, t.yellow, 50));
                break;
            case GHOST:
                setTextColor(t.sub);
                setBackgroundColor(0x00000000);
                setPadding(Ui.dp(getContext(), 18), 0, Ui.dp(getContext(), 18), 0);
                break;
            case TONAL:
                setTextColor(t.accent1);
                setBackground(Ui.round(getContext(), t.accentSoft, 50, 0, 0));
                break;
            case DANGER:
                // 危险动作：红字 + 淡红底，深浅两套主题都保证对比度
                setTextColor(t.red);
                setBackground(Ui.round(getContext(), Ui.alpha(t.red, 0.15f), 999,
                        Ui.alpha(t.red, 0.3f), 1));
                break;
            default:
                setTextColor(t.onAccent);
                setBackground(Ui.gradient(getContext(), t.accent1, t.accent2, 50));
                break;
        }
        if (style == PRIMARY || style == YELLOW) {
            GradientDrawable d = (GradientDrawable) getBackground();
            d.setCornerRadius(Ui.dpf(getContext(), 999));
        }
        setElevation(style == PRIMARY || style == YELLOW ? Ui.dpf(getContext(), 6) : 0f);
    }
}
