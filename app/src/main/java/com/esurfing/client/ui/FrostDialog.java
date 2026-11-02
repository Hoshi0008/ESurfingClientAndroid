package com.esurfing.client.ui;

import android.graphics.Bitmap;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 磨砂圆角弹窗：背后是当前界面的高斯模糊快照，卡片半透明、圆角、带描边，
 * 出现时从 0.86 缩放弹出，消失时反向收回。
 *
 * <p>两种形态：单行输入（设置页的「修改」）与列表选择（日志的「打开 / 导出」）。</p>
 */
public final class FrostDialog implements MainActivity.Overlay {

    /** 点击按钮的回调；返回 false 表示保持弹窗打开（例如校验不通过）。 */
    public interface Action {
        boolean run(FrostDialog dialog);
    }

    private final MainActivity act;
    private final Theme t;

    private FrameLayout overlay;
    private LinearLayout card;
    private EditText input;
    private LinearLayout listBox;

    private String[] labels;
    private String[] subs;
    private int selected = -1;
    private int preselectIndex = -1;

    private String titleText;
    private String hintText;
    private String initial = "";
    private boolean hasInput;
    private boolean password;
    private String positiveLabel = "确定";
    private String negativeLabel = "取消";
    private Action positive;
    private Action negative;

    private boolean shown;
    private boolean dismissed;

    public FrostDialog(MainActivity act) {
        this.act = act;
        this.t = act.theme;
    }

    // ------------------------------------------------------------------ 配置

    public FrostDialog title(String s) {
        titleText = s;
        return this;
    }

    public FrostDialog hint(String s) {
        hintText = s;
        return this;
    }

    /** 单行输入框；passwordField=true 时按密码显示。 */
    public FrostDialog input(String value, boolean passwordField) {
        initial = value == null ? "" : value;
        password = passwordField;
        hasInput = true;
        return this;
    }

    /** 列表选择；subs 可为 null。 */
    public FrostDialog list(String[] items, String[] subItems) {
        labels = items;
        subs = subItems;
        return this;
    }

    /** 列表默认选中项。 */
    public FrostDialog preselect(int index) {
        preselectIndex = index;
        return this;
    }

    public FrostDialog positive(String label, Action a) {
        positiveLabel = label;
        positive = a;
        return this;
    }

    public FrostDialog negative(String label, Action a) {
        negativeLabel = label;
        negative = a;
        return this;
    }

    public String text() {
        return input == null ? "" : input.getText().toString();
    }

    public int selected() {
        return selected;
    }

    // ------------------------------------------------------------------ 显示

    public void show() {
        if (shown) {
            return;
        }
        shown = true;

        Bitmap snapshot = Blur.capture(act.getWindow().getDecorView(), 7);

        overlay = new FrameLayout(act);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismiss());

        ImageView blur = new ImageView(act);
        blur.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.addView(blur, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Blur.apply(blur, snapshot, Ui.dpf(act, 22));

        View scrim = new View(act);
        scrim.setBackgroundColor(t.scrim);
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout holder = new FrameLayout(act);
        overlay.addView(holder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(act, 22), Ui.dp(act, 22), Ui.dp(act, 22), Ui.dp(act, 18));
        card.setBackground(Ui.round(act, t.dialogCard, 26, t.dialogStroke, 1));
        card.setElevation(Ui.dpf(act, 18));
        card.setClickable(true);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        clp.leftMargin = Ui.dp(act, 26);
        clp.rightMargin = Ui.dp(act, 26);
        holder.addView(card, clp);

        buildContent();

        card.setScaleX(0.86f);
        card.setScaleY(0.86f);
        card.setAlpha(0f);
        blur.setAlpha(0f);
        scrim.setAlpha(0f);

        act.pushOverlay(this);
        act.overlayLayer().addView(overlay);
        overlay.post(() -> {
            if (dismissed) {
                return;
            }
            DecelerateInterpolator dec = new DecelerateInterpolator(1.6f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(240).setInterpolator(dec).start();
            blur.animate().alpha(1f).setDuration(200).start();
            scrim.animate().alpha(1f).setDuration(200).start();
        });
    }

    private void buildContent() {
        if (titleText != null) {
            card.addView(Ui.text(act, titleText, 17f, t.txt, true),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (hintText != null && !hintText.isEmpty()) {
            TextView hint = Ui.text(act, hintText, 12f, t.muted, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(act, 6);
            card.addView(hint, lp);
        }
        if (hasInput) {
            buildInput();
        }
        if (labels != null) {
            buildList();
        }
        buildActions();
    }

    private void buildInput() {
        input = new EditText(act);
        input.setText(initial);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        input.setTextColor(t.txt);
        input.setHintTextColor(t.inputHint);
        input.setBackground(Ui.round(act, t.inputBg, 15, t.inputStroke, 1));
        input.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), 0);
        input.setSingleLine(true);
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setIncludeFontPadding(false);
        // 光标放在末尾，用户点开就能直接改
        input.setSelection(initial.length());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 50));
        lp.topMargin = Ui.dp(act, 18);
        card.addView(input, lp);
        input.postDelayed(this::showKeyboard, 220);
    }

    private void showKeyboard() {
        if (input == null || dismissed) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) act.getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            input.requestFocus();
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void buildList() {
        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxHeight = Ui.dp(act, 300);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxHeight,
                Ui.dp(act, 46) * Math.max(1, Math.min(labels.length, 5)) + Ui.dp(act, 12)));
        slp.topMargin = Ui.dp(act, 16);
        card.addView(scroll, slp);

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(act, 14), Ui.dp(act, 11), Ui.dp(act, 14), Ui.dp(act, 11));
            row.setBackground(Ui.round(act, 0x00000000, 13, 0, 0));

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.mono(act, labels[i], 12.5f, t.txt, true));
            if (subs != null && i < subs.length && subs[i] != null && !subs[i].isEmpty()) {
                TextView sub = Ui.text(act, subs[i], 10.5f, t.muted, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Ui.dp(act, 2);
                col.addView(sub, lp);
            }
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Dot check = new Dot(act, t.accent1);
            check.setVisibility(View.INVISIBLE);
            row.addView(check);

            row.setOnClickListener(v -> {
                selected = index;
                updateRows();
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rlp.topMargin = Ui.dp(act, 2);
            }
            listBox.addView(row, rlp);
        }
        if (labels.length > 0) {
            selected = preselectIndex >= 0 && preselectIndex < labels.length ? preselectIndex : 0;
            updateRows();
        }
    }

    private void updateRows() {
        if (listBox == null) {
            return;
        }
        for (int i = 0; i < listBox.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) listBox.getChildAt(i);
            boolean on = i == selected;
            row.setBackground(Ui.round(act, on ? t.accentSoft : 0x00000000, 13, 0, 0));
            if (row.getChildCount() > 1) {
                row.getChildAt(row.getChildCount() - 1).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private void buildActions() {
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(act, 20);
        card.addView(actions, alp);

        Pill no = new Pill(act, t, negativeLabel, Pill.GHOST);
        no.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        no.setPadding(Ui.dp(act, 18), 0, Ui.dp(act, 18), 0);
        actions.addView(no, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(act, 44)));
        no.setOnClickListener(v -> {
            if (negative != null) {
                negative.run(this);
            }
            dismiss();
        });

        Pill yes = new Pill(act, t, positiveLabel, Pill.PRIMARY);
        yes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        yes.setPadding(Ui.dp(act, 24), 0, Ui.dp(act, 24), 0);
        LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(act, 44));
        ylp.leftMargin = Ui.dp(act, 8);
        actions.addView(yes, ylp);
        yes.setOnClickListener(v -> {
            if (positive != null && !positive.run(this)) {
                return;
            }
            dismiss();
        });
    }

    // ------------------------------------------------------------------ 关闭

    @Override
    public void dismiss() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        hideKeyboard();
        if (overlay != null && overlay.getParent() != null) {
            card.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).setDuration(150).start();
            overlay.animate().alpha(0f).setDuration(170).withEndAction(() -> {
                ViewGroup parent = (ViewGroup) overlay.getParent();
                if (parent != null) {
                    parent.removeView(overlay);
                }
                act.popOverlay(this);
            }).start();
        } else {
            act.popOverlay(this);
        }
    }

    private void hideKeyboard() {
        if (input == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) act.getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    public boolean isShowing() {
        return shown && !dismissed;
    }
}
