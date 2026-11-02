package com.esurfing.client.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.esurfing.client.App;
import com.esurfing.client.R;
import com.esurfing.client.service.ServiceState;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * 唯一的 Activity：一个自绘的根布局 + 三个页面 + 底部选项卡 + 覆盖层（弹窗 / 浮窗 / 菜单）。
 *
 * <p>不使用 AndroidX / Fragment，页面切换、弹窗模糊、动画全部手写，
 * 目的是把 APK 压到 1MB 以内并让启动足够快。</p>
 */
public class MainActivity extends Activity {

    public static final int PAGE_MAIN = 0;
    public static final int PAGE_LOG = 1;
    public static final int PAGE_SETTINGS = 2;

    private static final int REQ_NOTIF = 11;
    private static final int REQ_EXPORT = 12;

    /** 覆盖层：能被返回键关掉的东西（弹窗、下拉菜单）。 */
    public interface Overlay {
        void dismiss();
    }

    public Theme theme;
    public final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private FrameLayout content;
    private FrameLayout overlayLayer;
    private BottomBar bar;

    private final Page[] pages = new Page[3];
    private int current = -1;

    private int insetTop;
    private int insetBottom;

    private final ArrayList<Overlay> overlays = new ArrayList<Overlay>();
    private Runnable pendingAfterNotif;
    private byte[] pendingExport;
    private View toastView;

    private final ServiceState.Listener stateListener = () -> handler.post(() -> {
        if (pages[PAGE_MAIN] instanceof MainPage) {
            ((MainPage) pages[PAGE_MAIN]).refresh();
        }
        maybeOpenEiaPortal();
    });

    /** 已经为哪个门户地址拉起过浏览器，避免重复弹。 */
    private String openedEiaPortal = "";

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        theme = Theme.of(this);
        // 主题必须在 super.onCreate 之前定下来：窗口背景与系统栏图标颜色由样式决定
        setTheme(theme.dark ? R.style.Theme_ESurfing_Dark : R.style.Theme_ESurfing);
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        // 关掉系统给导航栏自动加的半透明遮罩，否则底部会出现一条白边
        window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(theme.bg1);
        root.addView(new Backdrop(this, theme), match());

        content = new FrameLayout(this);
        root.addView(content, match());

        bar = new BottomBar(this, theme, index -> select(index, true));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 66));
        barLp.gravity = Gravity.BOTTOM;
        barLp.leftMargin = Ui.dp(this, 16);
        barLp.rightMargin = Ui.dp(this, 16);
        barLp.bottomMargin = Ui.dp(this, 14);
        root.addView(bar, barLp);

        overlayLayer = new FrameLayout(this);
        overlayLayer.setClipChildren(false);
        // 覆盖层要压在底部选项卡（8dp 阴影）之上，否则弹窗会被选项卡盖住一角
        overlayLayer.setElevation(Ui.dpf(this, 40));
        root.addView(overlayLayer, match());

        setContentView(root);
        // 系统栏图标明暗必须在 DecorView 挂上之后设置，否则 getInsetsController() 为 null
        applySystemBarAppearance();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets);
            if (!barAppearanceApplied) {
                // 只有 DecorView 真正挂上之后 getInsetsController() 才非空
                applySystemBarAppearance();
            }
            return insets;
        });

        select(PAGE_MAIN, false);
        // 冷启动自检：进程启动 → 界面搭好用了多少毫秒（日志里可见，便于排查性能回归）
        long since = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis();
        android.util.Log.i("ESurfing", "cold start: onCreate 完成，进程启动后 " + since + "ms");
    }

    private boolean barAppearanceApplied;

    private void applySystemBarAppearance() {
        WindowInsetsController ic = getWindow().getInsetsController();
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        // 深色主题要浅色图标，浅色主题要深色图标
        if (ic != null) {
            ic.setSystemBarsAppearance(theme.dark ? 0 : mask, mask);
            barAppearanceApplied = true;
        }
        // 兜底：个别 ROM 上 InsetsController 的 appearance 不生效，直接改 systemUiVisibility
        View decor = getWindow().getDecorView();
        int vis = decor.getSystemUiVisibility();
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        vis = theme.dark ? (vis & ~flags) : (vis | flags);
        decor.setSystemUiVisibility(vis);
        android.util.Log.i("ESurfing", "bars ic=" + (ic != null) + " dark=" + theme.dark + " vis=" + vis);
    }

    private void applyInsets(WindowInsets insets) {
        android.graphics.Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        insetTop = bars.top;
        insetBottom = bars.bottom;
        int barSpace = Ui.dp(this, 66 + 14 + 6);
        content.setPadding(0, insetTop, 0, insetBottom + barSpace);
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            int target = insetBottom + Ui.dp(this, 14);
            if (flp.bottomMargin != target) {
                flp.bottomMargin = target;
                bar.setLayoutParams(flp);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBarAppearance();
        ServiceState.addListener(stateListener);
        if (pages[PAGE_MAIN] == null) {
            ensure(PAGE_MAIN);
        }
        ((MainPage) pages[PAGE_MAIN]).refresh();
        maybeOpenEiaPortal();
    }

    @Override
    protected void onPause() {
        ServiceState.removeListener(stateListener);
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (theme.dark != Theme.isDark(this)) {
            recreate();
        }
    }

    @Override
    public void onBackPressed() {
        if (dismissTopOverlay()) {
            return;
        }
        if (current != PAGE_MAIN) {
            select(PAGE_MAIN, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 页面切换

    private Page ensure(int index) {
        if (pages[index] != null) {
            return pages[index];
        }
        switch (index) {
            case PAGE_LOG:
                pages[index] = new LogPage(this);
                break;
            case PAGE_SETTINGS:
                pages[index] = new SettingsPage(this);
                break;
            default:
                pages[index] = new MainPage(this);
                break;
        }
        return pages[index];
    }

    public void select(int index) {
        select(index, true);
    }

    public void select(int index, boolean animate) {
        if (index == current) {
            return;
        }
        Page next = ensure(index);
        View nextView = next.view();
        if (nextView.getParent() == null) {
            content.addView(nextView, match());
        }
        Page oldPage = current >= 0 ? pages[current] : null;
        if (oldPage != null) {
            oldPage.onHide();
        }
        next.onShow();

        if (animate && oldPage != null) {
            final View oldView = oldPage.view();
            nextView.setVisibility(View.VISIBLE);
            nextView.setAlpha(0f);
            nextView.setTranslationY(Ui.dp(this, 12));
            nextView.setScaleX(0.99f);
            nextView.setScaleY(0.99f);
            nextView.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(230).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            oldView.animate().alpha(0f).translationY(-Ui.dp(this, 8))
                    .setDuration(150)
                    .withEndAction(() -> {
                        oldView.setVisibility(View.GONE);
                        oldView.setAlpha(1f);
                        oldView.setTranslationY(0f);
                    }).start();
        } else {
            for (int i = 0; i < pages.length; i++) {
                if (pages[i] != null) {
                    pages[i].view().setVisibility(i == index ? View.VISIBLE : View.GONE);
                }
            }
        }
        current = index;
        bar.setSelected(index, animate);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ------------------------------------------------------------------ 覆盖层 / 浮窗

    public FrameLayout overlayLayer() {
        return overlayLayer;
    }

    public void pushOverlay(Overlay o) {
        overlays.add(o);
    }

    public void popOverlay(Overlay o) {
        overlays.remove(o);
    }

    public boolean dismissTopOverlay() {
        if (overlays.isEmpty()) {
            return false;
        }
        Overlay o = overlays.remove(overlays.size() - 1);
        o.dismiss();
        return true;
    }

    public int toastBottomMargin() {
        return insetBottom + Ui.dp(this, 66 + 14 + 18);
    }

    void setToast(View v) {
        toastView = v;
    }

    void clearToastIf(View v) {
        if (toastView == v) {
            toastView = null;
        }
    }

    void clearToast() {
        if (toastView != null) {
            ViewGroup p = (ViewGroup) toastView.getParent();
            if (p != null) {
                p.removeView(toastView);
            }
            toastView = null;
        }
    }

    // ------------------------------------------------------------------ 权限 / 文件

    /** 需要时先申请通知权限，再执行动作。 */
    public void requestNotifThen(Runnable action) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAfterNotif = action;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else if (action != null) {
            action.run();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            Runnable r = pendingAfterNotif;
            pendingAfterNotif = null;
            if (r != null) {
                r.run();
            }
        }
    }

    /** 用系统的「另存为」界面导出日志。 */
    public void exportLog(File file, byte[] data) {
        pendingExport = data;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 用通用二进制类型，避免系统按 MIME 给 .log 再补一个 .txt 后缀
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, file == null ? "ESurfing.log" : file.getName());
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            FloatToast.show(this, "没有可用的文件管理器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT) {
            return;
        }
        byte[] bytes = pendingExport;
        pendingExport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) {
            return;
        }
        Uri uri = data.getData();
        OutputStream out = null;
        boolean ok = false;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out != null) {
                out.write(bytes);
                out.flush();
                ok = true;
            }
        } catch (IOException | SecurityException e) {
            ok = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        FloatToast.show(this, ok ? "日志已导出" : "导出失败");
    }

    /** 保存设置后如果是主题变更，就地重建界面。 */
    public void recreateAfterThemeChange() {
        handler.postDelayed(this::recreate, 520);
    }

    /**
     * 后端判定连到了学校 eIA 准入网时，用默认浏览器打开它的首次认证门户。
     *
     * <p>只在地址较新（2 分钟内）时打开，避免用户切回来时突然弹浏览器。</p>
     */
    private void maybeOpenEiaPortal() {
        String url = ServiceState.eiaPortal;
        if (url == null || url.isEmpty()) {
            openedEiaPortal = "";
            return;
        }
        if (url.equals(openedEiaPortal)) {
            return;
        }
        if (System.currentTimeMillis() - ServiceState.eiaPortalAt > 120_000L) {
            return;
        }
        openedEiaPortal = url;
        openInBrowser(url);
    }

    /** 用系统默认浏览器打开地址（失败就提示）。 */
    public void openInBrowser(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            FloatToast.show(this, "已打开门户，请先在浏览器里完成认证");
        } catch (RuntimeException e) {
            FloatToast.show(this, "打开浏览器失败：" + url);
        }
    }

    @Override
    protected void onDestroy() {
        clearToast();
        super.onDestroy();
    }

    /** 供设置页读取：配置文件对象。 */
    public File configFile() {
        return App.get().configFile();
    }
}
