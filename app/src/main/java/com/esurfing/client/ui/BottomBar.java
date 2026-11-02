package com.esurfing.client.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.esurfing.client.R;

/** 底部选项卡：三个栏目 + 会滑动的选中胶囊 + 颜色渐变过渡。 */
public final class BottomBar extends FrameLayout {

    public interface OnSelect {
        void onSelect(int index);
    }

    private static final String[] TITLES = {"主界面", "日志", "设置"};
    private static final int[] ICONS = {
            R.drawable.ic_tab_home, R.drawable.ic_tab_log, R.drawable.ic_tab_settings,
    };

    private final Theme t;
    private final View indicator;
    private final LinearLayout[] tabs = new LinearLayout[3];
    private final ImageView[] icons = new ImageView[3];
    private final TextView[] labels = new TextView[3];
    private final ArgbEvaluator evaluator = new ArgbEvaluator();

    private int selected;
    private int itemWidth;
    private boolean laidOut;

    public BottomBar(Context c, Theme theme, OnSelect cb) {
        super(c);
        this.t = theme;
        setBackground(Ui.round(c, theme.bar, 24, theme.barStroke, 1));
        setElevation(Ui.dpf(c, 8));
        setPadding(Ui.dp(c, 7), Ui.dp(c, 7), Ui.dp(c, 7), Ui.dp(c, 7));

        indicator = new View(c);
        indicator.setBackground(Ui.round(c, theme.accentSoft, 18, 0, 0));
        addView(indicator, new FrameLayout.LayoutParams(0, 0));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout tab = new LinearLayout(c);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            if (theme.dark) {
                tab.setBackground(Ui.round(c, 0x00000000, 16, 0, 0));
            }
            ImageView icon = Ui.icon(c, ICONS[i], theme.muted, 21);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 21), Ui.dp(c, 21));
            icon.setLayoutParams(ilp);
            TextView label = Ui.text(c, TITLES[i], 10.5f, theme.muted, true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = Ui.dp(c, 3);
            tab.addView(icon);
            tab.addView(label, llp);
            tab.setOnClickListener(v -> cb.onSelect(index));
            row.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            tabs[i] = tab;
            icons[i] = icon;
            labels[i] = label;
        }
        applyColors(0, 1f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int innerW = w - getPaddingLeft() - getPaddingRight();
        int innerH = h - getPaddingTop() - getPaddingBottom();
        itemWidth = innerW / 3;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        lp.width = itemWidth;
        lp.height = innerH;
        lp.leftMargin = getPaddingLeft();
        lp.topMargin = getPaddingTop();
        indicator.setLayoutParams(lp);
        indicator.setTranslationX(selected * (float) itemWidth);
        laidOut = true;
    }

    public void setSelected(int index, boolean animate) {
        if (index < 0 || index > 2) {
            return;
        }
        int prev = selected;
        if (prev == index && laidOut) {
            return;
        }
        selected = index;
        float targetX = index * (float) itemWidth;
        if (animate) {
            indicator.animate().translationX(targetX).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(220);
            final int from = prev;
            final int to = index;
            va.addUpdateListener(a -> applyColorsAnimated(from, to, a.getAnimatedFraction()));
            va.start();
        } else {
            indicator.setTranslationX(targetX);
            applyColors(index, 1f);
        }
    }

    private void applyColorsAnimated(int from, int to, float f) {
        for (int i = 0; i < 3; i++) {
            float p;
            if (i == from) {
                p = 1f - f;
            } else if (i == to) {
                p = f;
            } else {
                p = 0f;
            }
            int color = (Integer) evaluator.evaluate(p, t.muted, t.accent1);
            icons[i].setColorFilter(color);
            labels[i].setTextColor(color);
            icons[i].setScaleX(0.94f + 0.06f * p);
            icons[i].setScaleY(0.94f + 0.06f * p);
        }
    }

    private void applyColors(int index, float f) {
        for (int i = 0; i < 3; i++) {
            boolean on = i == index;
            int color = on ? t.accent1 : t.muted;
            icons[i].setColorFilter(color);
            labels[i].setTextColor(color);
            icons[i].setScaleX(on ? 1f : 0.94f);
            icons[i].setScaleY(on ? 1f : 0.94f);
        }
    }
}
