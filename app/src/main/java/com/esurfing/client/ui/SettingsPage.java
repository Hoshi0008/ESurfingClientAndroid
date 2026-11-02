package com.esurfing.client.ui;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.esurfing.client.App;
import com.esurfing.client.AppPrefs;
import com.esurfing.client.core.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页。
 *
 * <p>分两块：最上面是<b>应用设置</b>（主题、开机自启，存在 SharedPreferences 里，
 * 改完立即生效，不提示重启）；下面是<b>网络配置</b>（{@code esurfing.conf} 里的全部字段，
 * 保存后立即写盘并提示重启服务生效）。</p>
 */
public final class SettingsPage implements Page {

    private final MainActivity act;
    private final Theme t;
    private final ScrollView scroll;
    private final List<Row> rows = new ArrayList<Row>();

    private static final class Row {
        final Config.Field field;
        final TextView value;

        Row(Config.Field field, TextView value) {
            this.field = field;
            this.value = value;
        }
    }

    public SettingsPage(MainActivity act) {
        this.act = act;
        this.t = act.theme;

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(act, 20), Ui.dp(act, 6), Ui.dp(act, 20), Ui.dp(act, 16));
        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(act, "设置", 21f, t.txt, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = Ui.dp(act, 4);
        col.addView(title, titleLp);

        // ---- 应用设置（独立于网络配置文件，改完立即生效）
        col.addView(sectionHeader("应用设置", "即时生效 · 不写进网络配置文件"));
        LinearLayout appCard = listCard();
        for (Config.Field f : Config.appFields()) {
            addRow(appCard, f);
        }
        col.addView(appCard, wrap());

        // ---- 网络配置
        col.addView(sectionHeader("网络配置",
                "共 " + Config.fields().size() + " 项 · 修改后立即写入配置文件，重启服务生效"));
        LinearLayout netCard = listCard();
        for (Config.Field f : Config.fields()) {
            addRow(netCard, f);
        }
        col.addView(netCard, wrap());
    }

    /** 避免多引一个 ViewGroup 常量。 */
    private View sectionHeader(String title, String subtitle) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(act, title, 13f, t.txt, true);
        head.setLetterSpacing(0.04f);
        TextView sub = Ui.text(act, subtitle, 10.5f, t.muted, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(act, 3);
        box.addView(head);
        box.addView(sub, subLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 18);
        lp.bottomMargin = Ui.dp(act, 8);
        lp.leftMargin = Ui.dp(act, 4);
        box.setLayoutParams(lp);
        return box;
    }

    private LinearLayout listCard() {
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.round(act, t.card, 22, t.cardStroke, 1));
        list.setElevation(Ui.dpf(act, 3));
        return list;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addRow(LinearLayout card, Config.Field f) {
        if (card.getChildCount() > 0) {
            View divider = new View(act);
            divider.setBackgroundColor(t.divider);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dlp.leftMargin = Ui.dp(act, 18);
            card.addView(divider, dlp);
        }
        card.addView(buildRow(f));
    }

    private View buildRow(Config.Field f) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 18), Ui.dp(act, 14), Ui.dp(act, 18), Ui.dp(act, 14));

        LinearLayout nameCol = new LinearLayout(act);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                Ui.dp(act, 112), ViewGroup.LayoutParams.WRAP_CONTENT);
        TextView key = Ui.mono(act, f.key, 13.5f, t.txt, true);
        TextView label = Ui.text(act, f.label, 10.5f, t.muted, false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = Ui.dp(act, 3);
        nameCol.addView(key);
        nameCol.addView(label, labelLp);
        row.addView(nameCol, nameLp);

        TextView value = Ui.text(act, "", 13f, t.sub, false);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView edit = Ui.text(act, "修改", 13f, t.accent1, true);
        edit.setPadding(Ui.dp(act, 12), Ui.dp(act, 4), 0, Ui.dp(act, 4));
        row.addView(edit);

        rows.add(new Row(f, value));
        row.setOnClickListener(v -> openEdit(f));
        edit.setOnClickListener(v -> openEdit(f));
        return row;
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShow() {
        refreshValues();
    }

    @Override
    public void onHide() {
        // 无需处理
    }

    private void refreshValues() {
        for (Row r : rows) {
            String raw = currentValue(r.field);
            r.value.setText(display(r.field, raw));
            r.value.setTextColor(raw.isEmpty() ? t.muted : t.sub);
        }
    }

    /** 应用级设置从 SharedPreferences 读，网络配置从 esurfing.conf 读。 */
    private String currentValue(Config.Field f) {
        if (!f.appLevel) {
            return App.get().config().get(f.key);
        }
        if ("theme".equals(f.key)) {
            return AppPrefs.theme(act);
        }
        if ("auto_start".equals(f.key)) {
            return AppPrefs.autoStart(act) ? "true" : "false";
        }
        return "";
    }

    /** @return 是否写入成功 */
    private boolean saveValue(Config.Field f, String value) {
        if (f.appLevel) {
            if ("theme".equals(f.key)) {
                AppPrefs.setTheme(act, value);
            } else if ("auto_start".equals(f.key)) {
                AppPrefs.setAutoStart(act, "true".equalsIgnoreCase(value));
            }
            return true;
        }
        Config c = App.get().config();
        c.set(f.key, value);
        return c.save();
    }

    /** 字段值的展示形式：密码只显示掩码，枚举值显示中文名。 */
    private String display(Config.Field f, String raw) {
        if ("passwd".equals(f.key)) {
            if (raw.isEmpty()) {
                return "（空）";
            }
            int n = Math.min(12, Math.max(6, raw.length()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sb.append('•');
            }
            return sb.toString();
        }
        return raw.isEmpty() ? "（空）" : f.display(raw);
    }

    // ------------------------------------------------------------------ 修改弹窗

    private void openEdit(Config.Field f) {
        final String current = currentValue(f);

        FrostDialog dialog = new FrostDialog(act).title("修改" + f.key);

        if (f.options != null) {
            // 枚举字段用下拉选择，避免手输出错
            StringBuilder hint = new StringBuilder(f.hint);
            if (f.labels != null) {
                hint.append("（可选：").append(TextUtils.join(" / ", f.labels)).append("）");
            }
            int index = f.indexOf(current);
            dialog.hint(hint.toString())
                    .select(f.options, f.labels, index < 0 ? 0 : index);
        } else {
            dialog.hint(f.hint)
                    .input(current, "passwd".equals(f.key));
        }

        dialog.positive("保存", d -> {
            String value = d.text().trim();
            String error = f.validate(value);
            if (error != null) {
                FloatToast.show(act, error);
                return false;
            }
            if (!saveValue(f, value)) {
                FloatToast.show(act, "写入配置文件失败");
                return false;
            }
            refreshValues();
            if (f.appLevel) {
                // 应用级设置立即生效，不提示重启服务
                FloatToast.show(act, "已保存");
                if ("theme".equals(f.key)) {
                    act.recreateAfterThemeChange();
                }
            } else {
                FloatToast.show(act, "重启服务后生效");
            }
            return true;
        }).negative("取消", null).show();
    }
}
