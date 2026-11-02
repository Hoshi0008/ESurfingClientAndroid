package com.esurfing.client.ui;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 逐渐消失的小浮窗（不是弹窗）：淡入 → 停留 → 上移淡出。 */
public final class FloatToast {

    private FloatToast() {
    }

    public static void show(MainActivity act, CharSequence text) {
        if (act == null || act.isFinishing() || TextUtils.isEmpty(text)) {
            return;
        }
        FrameLayout layer = act.overlayLayer();
        if (layer == null) {
            return;
        }
        act.clearToast();

        TextView v = new TextView(act);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        v.setTextColor(act.theme.toastTxt);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(Ui.dp(act, 20), Ui.dp(act, 11), Ui.dp(act, 20), Ui.dp(act, 11));
        v.setBackground(Ui.round(act, act.theme.toastBg, 999, 0x1AFFFFFF, 1));
        v.setElevation(Ui.dpf(act, 12));
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(act, 14));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = act.toastBottomMargin();
        lp.leftMargin = Ui.dp(act, 32);
        lp.rightMargin = Ui.dp(act, 32);
        layer.addView(v, lp);
        act.setToast(v);

        v.animate().alpha(1f).translationY(0f).setDuration(180).start();
        v.postDelayed(() -> {
            if (v.getParent() == null) {
                return;
            }
            v.animate().alpha(0f).translationY(-Ui.dp(act, 10))
                    .setDuration(430)
                    .withEndAction(() -> {
                        ViewGroup p = (ViewGroup) v.getParent();
                        if (p != null) {
                            p.removeView(v);
                        }
                        act.clearToastIf(v);
                    }).start();
        }, 1900);
    }
}
