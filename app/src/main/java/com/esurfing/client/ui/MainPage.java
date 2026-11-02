package com.esurfing.client.ui;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.esurfing.client.App;
import com.esurfing.client.R;
import com.esurfing.client.core.Config;
import com.esurfing.client.service.AuthService;
import com.esurfing.client.service.NetInfo;
import com.esurfing.client.service.ServiceState;
import com.esurfing.client.service.WifiNames;

import java.util.Locale;

/**
 * 主界面。
 *
 * <ul>
 *     <li>首次使用（或 user/passwd 为空）：两个输入框 + 居中的「确认」；</li>
 *     <li>未启动服务：红色「未启动服务」+ 黄色圆角「启动」；</li>
 *     <li>运行中：绿色「服务运行中」+ 下方实时的运行时长（hh:mm:ss）+「停止服务」。</li>
 * </ul>
 */
public final class MainPage implements Page {

    private final MainActivity act;
    private final Theme t;
    private final ScrollView scroll;
    private final LinearLayout col;
    private final LinearLayout statusCard;
    private final LinearLayout infoCard;
    private final LinearLayout keepCard;
    private final TextView chip;

    private String stateKey = "";
    private EditText userBox;
    private EditText passBox;

    /** 运行时长控件与每秒走针。 */
    private TextView elapsed;
    private boolean visible;
    private boolean tickerRunning;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!tickerRunning) {
                return;
            }
            updateElapsed();
            act.handler.postDelayed(this, 1000);
        }
    };

    /** WiFi 名称会换（切网络），页面可见时每 8 秒刷一次。 */
    private final Runnable chipTicker = new Runnable() {
        @Override
        public void run() {
            if (!visible) {
                return;
            }
            refresh();
            act.handler.postDelayed(this, 8000);
        }
    };

    public MainPage(MainActivity act) {
        this.act = act;
        this.t = act.theme;

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(act, 20), Ui.dp(act, 6), Ui.dp(act, 20), Ui.dp(act, 16));
        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 头部：logo + 标题 + 网络标签
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(act, 2), Ui.dp(act, 6), Ui.dp(act, 2), Ui.dp(act, 18));

        FrameLayout logo = new FrameLayout(act);
        logo.setBackground(Ui.gradient(act, t.accent1, t.accent2, 13));
        logo.setElevation(Ui.dpf(act, 6));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                Ui.dp(act, 42), Ui.dp(act, 42));
        ImageView mark = Ui.icon(act, R.drawable.ic_launcher_fg, 0xFFFFFFFF, 26);
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(
                Ui.dp(act, 26), Ui.dp(act, 26));
        markLp.gravity = Gravity.CENTER;
        logo.addView(mark, markLp);
        head.addView(logo, logoLp);

        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titlesLp.leftMargin = Ui.dp(act, 12);
        TextView title = Ui.text(act, "校园网", 19f, t.txt, true);
        TextView sub = Ui.text(act, "广东天翼 · cdcportal", 11.5f, t.muted, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(act, 3);
        titles.addView(title);
        titles.addView(sub, subLp);
        head.addView(titles, titlesLp);

        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        chip = Ui.text(act, "WiFi", 11.5f, t.chipTxt, true);
        chip.setBackground(Ui.round(act, t.chipBg, 999, 0, 0));
        chip.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth(Ui.dp(act, 160));
        // 没授权时点一下可以再申请一次（拿到权限就能显示真实 SSID）
        chip.setOnClickListener(v -> act.requestWifiNamePermission(true));
        head.addView(chip);
        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 状态卡片
        statusCard = card();
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        col.addView(statusCard, scLp);

        // ---- 信息卡片
        infoCard = card();
        infoCard.setPadding(Ui.dp(act, 18), Ui.dp(act, 4), Ui.dp(act, 18), Ui.dp(act, 4));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icLp.topMargin = Ui.dp(act, 14);
        col.addView(infoCard, icLp);

        col.addView(new View(act), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 后台保活
        TextView section = Ui.text(act, "后台保活", 11.5f, t.muted, true);
        section.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = Ui.dp(act, 18);
        secLp.bottomMargin = Ui.dp(act, 8);
        secLp.leftMargin = Ui.dp(act, 4);
        col.addView(section, secLp);

        keepCard = card();
        keepCard.setPadding(Ui.dp(act, 18), Ui.dp(act, 4), Ui.dp(act, 18), Ui.dp(act, 4));
        col.addView(keepCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(act, t.card, 24, t.cardStroke, 1));
        c.setElevation(Ui.dpf(act, 3));
        return c;
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShow() {
        visible = true;
        refresh();
        act.handler.removeCallbacks(chipTicker);
        act.handler.postDelayed(chipTicker, 8000);
    }

    @Override
    public void onHide() {
        visible = false;
        act.handler.removeCallbacks(chipTicker);
        syncTicker();
    }

    // ------------------------------------------------------------------ 刷新

    public void refresh() {
        if (act.isFinishing()) {
            return;
        }
        Config c = App.get().config();
        boolean ready = c.ready();
        boolean running = ServiceState.running;
        NetInfo net = NetInfo.read(act);
        boolean wifi = net.wifi;
        // Wi-Fi 连没连上也进 key：没连时启动按钮要禁用，连上后要能变回可点
        String key = !ready ? "first"
                : (running ? "run:" + ServiceState.startTime : "stop:" + (wifi ? 1 : 0));
        if (!key.equals(stateKey)) {
            stateKey = key;
            rebuildStatus(c, ready, running, wifi);
        }
        rebuildInfo(c, running);
        rebuildKeep(c);
        updateChip(c, wifi);
        syncTicker();
    }

    private void updateChip(Config c, boolean wifi) {
        if (!wifi) {
            chip.setText("未连接 WiFi");
            return;
        }
        String ssid = WifiNames.currentSsid(act);
        if (ssid == null) {
            // 没拿到 SSID（未授权 / 系统限制）时退化显示，配置里记过的名字优先
            String cfgSsid = c.get("ssid").trim();
            ssid = cfgSsid.isEmpty() ? "WiFi 已连接" : cfgSsid;
        }
        chip.setText(ssid);
    }

    private void rebuildStatus(Config c, boolean ready, boolean running, boolean wifi) {
        statusCard.removeAllViews();
        userBox = null;
        passBox = null;
        elapsed = null;
        int pad = Ui.dp(act, 22);
        statusCard.setPadding(pad, Ui.dp(act, 30), pad, Ui.dp(act, 28));
        statusCard.setGravity(Gravity.CENTER_HORIZONTAL);

        if (!ready) {
            buildFirstRun();
        } else if (running) {
            buildRunning();
        } else {
            buildStopped(wifi);
        }
    }

    private void buildFirstRun() {
        TextView title = Ui.text(act, "首次使用", 22f, t.txt, true);
        title.setGravity(Gravity.CENTER);
        statusCard.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = Ui.text(act, "请输入校园网账号与密码，保存后即可启动认证服务。",
                12.5f, t.sub, false);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = Ui.dp(act, 8);
        descLp.bottomMargin = Ui.dp(act, 4);
        statusCard.addView(desc, descLp);

        userBox = input("账号（学号）", false);
        passBox = input("密码", true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 50));
        inputLp.topMargin = Ui.dp(act, 14);
        statusCard.addView(userBox, inputLp);
        LinearLayout.LayoutParams inputLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 50));
        inputLp2.topMargin = Ui.dp(act, 10);
        statusCard.addView(passBox, inputLp2);

        Pill ok = new Pill(act, t, "确认", Pill.PRIMARY);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.addView(ok, new LinearLayout.LayoutParams(
                Ui.dp(act, 200), Ui.dp(act, 50)));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = Ui.dp(act, 22);
        statusCard.addView(wrap, wrapLp);
        ok.setOnClickListener(v -> onConfirmCredentials());
    }

    private void onConfirmCredentials() {
        if (userBox == null || passBox == null) {
            return;
        }
        String user = userBox.getText().toString().trim();
        String pass = passBox.getText().toString().trim();
        if (user.isEmpty() || pass.isEmpty()) {
            FloatToast.show(act, "账号和密码都要填写");
            return;
        }
        Config c = App.get().config();
        c.set("user", user);
        c.set("passwd", pass);
        if (!c.save()) {
            FloatToast.show(act, "写入配置文件失败");
            return;
        }
        FloatToast.show(act, "配置已保存");
        refresh();
    }

    private void buildStopped(boolean wifi) {
        statusCard.addView(statusRow("未启动服务", t.red, 27f));
        Pill start = new Pill(act, t, "启动", Pill.YELLOW);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.addView(start, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(act, 50)));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = Ui.dp(act, 22);
        statusCard.addView(wrap, wrapLp);

        if (!wifi) {
            // 没有 Wi-Fi 就不让启动：认证只走 WLAN，启了也只会空转
            start.setEnabled(false);
            start.setAlpha(0.45f);
            start.setOnClickListener(null);
            TextView hint = Ui.text(act, "未连接 WiFi，请先连接校园网 Wi-Fi", 12f, t.muted, false);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = Ui.dp(act, 12);
            statusCard.addView(hint, hintLp);
            return;
        }
        start.setOnClickListener(v -> act.requestNotifThen(
                () -> AuthService.start(act, AuthService.ACTION_START)));
    }

    private void buildRunning() {
        statusCard.addView(statusRow("服务运行中", t.green, 27f));

        // 运行时长（每秒刷新），而不是启动时刻
        LinearLayout timeRow = new LinearLayout(act);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = Ui.dp(act, 12);
        TextView pre = Ui.text(act, "服务运行时长 ", 13f, t.sub, false);
        elapsed = Ui.mono(act,
                formatDuration(System.currentTimeMillis() - ServiceState.startTime),
                15f, t.txt, true);
        elapsed.setLetterSpacing(0.06f);
        timeRow.addView(pre);
        timeRow.addView(elapsed);
        statusCard.addView(timeRow, trLp);

        Pill stop = new Pill(act, t, "停止服务", Pill.DANGER);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.addView(stop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(act, 50)));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = Ui.dp(act, 22);
        statusCard.addView(wrap, wrapLp);
        stop.setOnClickListener(v -> confirmStop());
        stop.setOnLongClickListener(v -> {
            // 长按仍然可以就地重新启动（不常用，但省得先去停止再启动）
            AuthService.start(act, AuthService.ACTION_RESTART);
            FloatToast.show(act, "正在重新启动服务");
            return true;
        });
        syncTicker();
    }

    /** 时:分:秒，小时位不封顶。 */
    private static String formatDuration(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d",
                total / 3600, (total % 3600) / 60, total % 60);
    }

    private void updateElapsed() {
        if (elapsed != null && ServiceState.running) {
            elapsed.setText(formatDuration(System.currentTimeMillis() - ServiceState.startTime));
        }
    }

    /** 运行时长每秒走一格；页面不可见或服务停了就停表。 */
    private void syncTicker() {
        boolean should = visible && ServiceState.running && elapsed != null;
        if (should && !tickerRunning) {
            tickerRunning = true;
            updateElapsed();
            act.handler.postDelayed(ticker, 1000);
        } else if (!should && tickerRunning) {
            tickerRunning = false;
            act.handler.removeCallbacks(ticker);
        }
    }

    private void confirmStop() {
        new FrostDialog(act)
                .title("停止服务")
                .hint("停止后会先向 BRAS 发送下线请求，再结束认证服务。")
                .positive("停止", dialog -> {
                    AuthService.start(act, AuthService.ACTION_STOP);
                    FloatToast.show(act, "已请求停止服务");
                    return true;
                })
                .negative("取消", null)
                .show();
    }

    /** 状态大字：一个圆点 + 文本，水平居中。 */
    private View statusRow(String text, int color, float sizeSp) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Dot dot = new Dot(act, color);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                Ui.dp(act, 18), Ui.dp(act, 18));
        dotLp.rightMargin = Ui.dp(act, 6);
        row.addView(dot, dotLp);
        TextView label = Ui.text(act, text, sizeSp, color, true);
        row.addView(label);
        return row;
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        e.setTextColor(t.txt);
        e.setHintTextColor(t.inputHint);
        e.setBackground(Ui.round(act, t.inputBg, 15, t.inputStroke, 1));
        e.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), 0);
        e.setSingleLine(true);
        e.setIncludeFontPadding(false);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private void rebuildInfo(Config c, boolean running) {
        infoCard.removeAllViews();
        String ip = TextUtils.isEmpty(ServiceState.ip) ? "—" : ServiceState.ip;
        addRow(infoCard, "本机地址", ip, true, t.txt, null);
        String server = ServiceState.server;
        if (TextUtils.isEmpty(server)) {
            String conf = c.get("servers").trim();
            server = conf.isEmpty() ? "自动探测" : conf;
        }
        addRow(infoCard, "认证服务器", server, true, t.txt, null);
        if (running) {
            String phase = TextUtils.isEmpty(ServiceState.phase) ? "运行中" : ServiceState.phase;
            addRow(infoCard, "当前状态", phase, false, t.green, null);
        } else {
            String user = c.get("user").trim();
            addRow(infoCard, "账号", user.isEmpty() ? "—" : user, false, t.txt, null);
        }
    }

    private void rebuildKeep(Config c) {
        keepCard.removeAllViews();
        addRow(keepCard, "前台服务", "已开启", false, t.green, null);
        boolean ignoring = AuthService.isIgnoringBatteryOptimizations(act);
        addRow(keepCard, "电池优化白名单",
                ignoring ? "已授权" : "点此授权 ›",
                false, ignoring ? t.green : t.accent1,
                ignoring ? null : this::requestBatteryOptimization);
        boolean autoStart = com.esurfing.client.AppPrefs.autoStart(act);
        addRow(keepCard, "开机自启", autoStart ? "已开启" : "已关闭",
                false, autoStart ? t.green : t.muted, null);
    }

    private void requestBatteryOptimization() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (RuntimeException e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                FloatToast.show(act, "请在系统设置里手动加入白名单");
            }
        }
    }

    private void addRow(LinearLayout card, String key, String value, boolean mono,
                        int valueColor, Runnable onClick) {
        if (card.getChildCount() > 0) {
            View divider = new View(act);
            divider.setBackgroundColor(t.divider);
            card.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 13), 0, Ui.dp(act, 13));

        TextView k = Ui.text(act, key, 13f, t.muted, false);
        row.addView(k);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView v = mono ? Ui.mono(act, value, 13f, valueColor, true)
                : Ui.text(act, value, 13f, valueColor, true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END);
        v.setMaxWidth(Ui.dp(act, 190));
        row.addView(v);

        if (onClick != null) {
            row.setOnClickListener(v1 -> onClick.run());
        }
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
