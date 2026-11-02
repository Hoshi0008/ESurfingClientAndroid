package com.esurfing.client.ui;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.esurfing.client.App;
import com.esurfing.client.R;
import com.esurfing.client.core.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志页。
 *
 * <p>顶部 Frame 里居中显示「当前日志」，右侧三个点按钮弹出下拉菜单；
 * 下面是一个只读文本框，后端每写一行日志就往这个缓冲区里追加（实时滚动跟随）。</p>
 */
public final class LogPage implements Page {

    private static final int MAX_CHARS = 300 * 1024;

    private final MainActivity act;
    private final Theme t;
    private final LinearLayout root;
    private final FrameLayout moreButton;
    private final ScrollView scroll;
    private final TextView box;
    private final TextView empty;

    private final SimpleDateFormat stamp =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private boolean follow = true;
    private File shownFile;
    private FrameLayout menuOverlay;

    private final MainActivity.Overlay menuToken = new MainActivity.Overlay() {
        @Override
        public void dismiss() {
            hideMenu();
        }
    };

    private final Log.Sink sink = this::onLine;

    public LogPage(MainActivity act) {
        this.act = act;
        this.t = act.theme;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---- 顶部 Frame：标题居中 + 右侧三个点
        FrameLayout top = new FrameLayout(act);
        top.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), Ui.dp(act, 16));

        TextView title = Ui.text(act, "当前日志", 16f, t.txt, true);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.gravity = Gravity.CENTER;
        top.addView(title, tlLp);

        moreButton = new FrameLayout(act);
        moreButton.setBackground(Ui.round(act, t.card, 12, t.cardStroke, 1));
        moreButton.setElevation(Ui.dpf(act, 2));
        ImageView moreIcon = Ui.icon(act, R.drawable.ic_more, t.txt, 18);
        FrameLayout.LayoutParams miLp = new FrameLayout.LayoutParams(
                Ui.dp(act, 18), Ui.dp(act, 18));
        miLp.gravity = Gravity.CENTER;
        moreButton.addView(moreIcon, miLp);
        FrameLayout.LayoutParams mbLp = new FrameLayout.LayoutParams(
                Ui.dp(act, 38), Ui.dp(act, 38));
        mbLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        top.addView(moreButton, mbLp);
        moreButton.setOnClickListener(v -> toggleMenu());
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 只读日志文本框
        scroll = new ScrollView(act);
        scroll.setBackground(Ui.round(act, t.logBg, 20, t.cardStroke, 1));
        scroll.setElevation(Ui.dpf(act, 3));
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFillViewport(false);

        box = new TextView(act);
        box.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f);
        box.setTextColor(t.logTxt);
        box.setTypeface(Typeface.MONOSPACE);
        box.setLineSpacing(Ui.dpf(act, 2f), 1.08f);
        box.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 18));
        box.setTextIsSelectable(true);
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = Ui.text(act, "暂无日志。启动服务后，这里会实时显示当前日志。",
                12.5f, Ui.alpha(t.logTxt, 0.6f), false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);

        FrameLayout boxWrap = new FrameLayout(act);
        boxWrap.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        boxWrap.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        boxLp.leftMargin = Ui.dp(act, 16);
        boxLp.rightMargin = Ui.dp(act, 16);
        root.addView(boxWrap, boxLp);
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShow() {
        Log.addSink(sink);
        reload();
    }

    @Override
    public void onHide() {
        Log.removeSink(sink);
        hideMenu();
    }

    // ------------------------------------------------------------------ 内容

    private void reload() {
        Log current = Log.current();
        if (current != null) {
            shownFile = current.file();
            follow = true;
            setContent(current.snapshot());
            return;
        }
        List<File> files = Log.list(App.get().logDir());
        follow = false;
        if (!files.isEmpty()) {
            shownFile = files.get(0);
            setContent(Log.read(files.get(0)));
        } else {
            shownFile = null;
            setContent("");
        }
    }

    private void setContent(String text) {
        String value = text == null ? "" : text;
        if (value.length() > MAX_CHARS) {
            value = "…（只显示最后 " + (MAX_CHARS / 1024) + "KB）\n"
                    + value.substring(value.length() - MAX_CHARS);
        }
        box.setText(value);
        empty.setVisibility(value.isEmpty() ? View.VISIBLE : View.GONE);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void onLine(String line) {
        act.handler.post(() -> {
            if (!follow) {
                return;
            }
            Log current = Log.current();
            if (current == null || shownFile == null || !shownFile.equals(current.file())) {
                reload();
                return;
            }
            if (box.length() > MAX_CHARS) {
                CharSequence tail = box.getText()
                        .subSequence(box.length() - MAX_CHARS / 2, box.length());
                setContent(tail.toString());
            }
            boolean nearBottom = isNearBottom();
            box.append(line + "\n");
            empty.setVisibility(View.GONE);
            if (nearBottom) {
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private boolean isNearBottom() {
        View child = scroll.getChildAt(0);
        if (child == null) {
            return true;
        }
        return scroll.getScrollY() + scroll.getHeight()
                >= child.getHeight() - Ui.dp(act, 40);
    }

    // ------------------------------------------------------------------ 下拉菜单

    private void toggleMenu() {
        if (menuOverlay != null) {
            hideMenu();
            return;
        }
        FrameLayout layer = act.overlayLayer();
        if (layer == null) {
            return;
        }
        menuOverlay = new FrameLayout(act);
        View catcher = new View(act);
        catcher.setOnClickListener(v -> hideMenu());
        menuOverlay.addView(catcher, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout menu = new LinearLayout(act);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(Ui.dp(act, 7), Ui.dp(act, 7), Ui.dp(act, 7), Ui.dp(act, 7));
        // 下拉菜单压在深色日志框上，用接近不透明的表面色保证文字对比度
        menu.setBackground(Ui.round(act, Ui.alpha(t.dialogCard, 0.97f), 16, t.cardStroke, 1));
        menu.setElevation(Ui.dpf(act, 14));
        menu.addView(menuItem("打开日志", R.drawable.ic_open_log, this::openLogDialog));
        menu.addView(menuItem("导出日志", R.drawable.ic_export_log, this::exportLogDialog));

        int[] loc = new int[2];
        moreButton.getLocationInWindow(loc);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                Ui.dp(act, 172), ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.gravity = Gravity.TOP | Gravity.END;
        mlp.topMargin = loc[1] + moreButton.getHeight() + Ui.dp(act, 6);
        mlp.rightMargin = Ui.dp(act, 20);
        menuOverlay.addView(menu, mlp);

        layer.addView(menuOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        act.pushOverlay(menuToken);

        menu.setPivotX(Ui.dp(act, 172));
        menu.setPivotY(0);
        menu.setScaleX(0.94f);
        menu.setScaleY(0.94f);
        menu.setAlpha(0f);
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(170).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private View menuItem(String text, int iconRes, Runnable action) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 12), Ui.dp(act, 11), Ui.dp(act, 12), Ui.dp(act, 11));
        row.setBackground(Ui.round(act, 0x00000000, 11, 0, 0));
        row.addView(Ui.icon(act, iconRes, t.sub, 15));
        TextView label = Ui.text(act, text, 13.5f, t.txt, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(act, 9);
        row.addView(label, lp);
        row.setOnClickListener(v -> {
            hideMenu();
            action.run();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(act, 2);
        row.setLayoutParams(rlp);
        return row;
    }

    private void hideMenu() {
        if (menuOverlay == null) {
            return;
        }
        final FrameLayout overlay = menuOverlay;
        menuOverlay = null;
        act.popOverlay(menuToken);
        overlay.animate().alpha(0f).setDuration(130).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) {
                parent.removeView(overlay);
            }
        }).start();
    }

    // ------------------------------------------------------------------ 打开 / 导出

    private void openLogDialog() {
        final List<File> files = Log.list(App.get().logDir());
        if (files.isEmpty()) {
            FloatToast.show(act, "还没有日志文件");
            return;
        }
        String[] labels = new String[files.size()];
        String[] subs = new String[files.size()];
        int preselect = 0;
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            labels[i] = f.getName();
            subs[i] = Ui.size(f.length()) + " · " + stamp.format(new Date(f.lastModified()));
            if (shownFile != null && shownFile.equals(f)) {
                preselect = i;
            }
        }
        new FrostDialog(act)
                .title("打开日志")
                .hint("选择一个日志文件，在下方文本框中查看")
                .list(labels, subs)
                .preselect(preselect)
                .positive("打开", dialog -> {
                    int index = dialog.selected();
                    if (index < 0 || index >= files.size()) {
                        FloatToast.show(act, "请先选择文件");
                        return false;
                    }
                    showFile(files.get(index));
                    return true;
                })
                .negative("取消", null)
                .show();
    }

    private void showFile(File file) {
        shownFile = file;
        Log current = Log.current();
        if (current != null && file.equals(current.file())) {
            follow = true;
            setContent(current.snapshot());
            FloatToast.show(act, "正在跟随当前日志");
        } else {
            follow = false;
            setContent(Log.read(file));
            FloatToast.show(act, "已打开 " + file.getName());
        }
    }

    private void exportLogDialog() {
        final List<File> files = Log.list(App.get().logDir());
        if (files.isEmpty()) {
            FloatToast.show(act, "还没有日志文件");
            return;
        }
        String[] labels = new String[files.size()];
        String[] subs = new String[files.size()];
        int preselect = 0;
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            labels[i] = f.getName();
            subs[i] = Ui.size(f.length()) + " · " + stamp.format(new Date(f.lastModified()));
            if (shownFile != null && shownFile.equals(f)) {
                preselect = i;
            }
        }
        new FrostDialog(act)
                .title("导出日志")
                .hint("选择要导出的日志文件，然后挑一个保存位置")
                .list(labels, subs)
                .preselect(preselect)
                .positive("导出", dialog -> {
                    int index = dialog.selected();
                    if (index < 0 || index >= files.size()) {
                        FloatToast.show(act, "请先选择文件");
                        return false;
                    }
                    File f = files.get(index);
                    Log current = Log.current();
                    String text = current != null && f.equals(current.file())
                            ? current.snapshot()
                            : Log.read(f);
                    act.exportLog(f, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return true;
                })
                .negative("取消", null)
                .show();
    }

    /** 供外部（例如导出回调）判断是否有可显示内容。 */
    @SuppressWarnings("unused")
    private boolean hasContent() {
        return !TextUtils.isEmpty(box.getText());
    }
}
