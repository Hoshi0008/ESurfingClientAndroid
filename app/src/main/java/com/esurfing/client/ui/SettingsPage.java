package com.esurfing.client.ui;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.esurfing.client.App;
import com.esurfing.client.core.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：从上到下逐行显示所有配置项
 * {@code <字段名> <字段值>  修改}，点「修改」弹出磨砂弹窗，
 * 保存后立即写入配置文件并提示「重启服务后生效」（小浮窗，不是弹窗）。
 */
public final class SettingsPage implements Page {

    private final MainActivity act;
    private final Theme t;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final List<Row> rows = new ArrayList<Row>();

    private static final class Row {
        Config.Field field;
        TextView value;

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
        col.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = Ui.text(act,
                "共 " + Config.fields().size() + " 项配置 · 修改后立即写入配置文件，重启服务生效",
                11.5f, t.muted, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(act, 4);
        subLp.bottomMargin = Ui.dp(act, 16);
        col.addView(subtitle, subLp);

        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.round(act, t.card, 22, t.cardStroke, 1));
        list.setElevation(Ui.dpf(act, 3));
        col.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildRows();
    }

    private void buildRows() {
        List<Config.Field> fields = Config.fields();
        for (int i = 0; i < fields.size(); i++) {
            Config.Field f = fields.get(i);
            if (i > 0) {
                View divider = new View(act);
                divider.setBackgroundColor(t.divider);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dlp.leftMargin = Ui.dp(act, 18);
                list.addView(divider, dlp);
            }
            list.addView(buildRow(f));
        }
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

        Row r = new Row(f, value);
        rows.add(r);
        row.setOnClickListener(v -> openEdit(f));
        edit.setOnClickListener(v -> openEdit(f));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rlp);
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
        Config c = App.get().config();
        for (Row r : rows) {
            String raw = c.get(r.field.key);
            String shown = display(r.field, raw);
            r.value.setText(shown);
            boolean empty = raw.isEmpty();
            r.value.setTextColor(empty ? t.muted : t.sub);
        }
    }

    /** 字段值的展示形式：密码只显示掩码。 */
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
        return raw.isEmpty() ? "（空）" : raw;
    }

    // ------------------------------------------------------------------ 修改弹窗

    private void openEdit(Config.Field f) {
        final Config c = App.get().config();
        final String current = c.get(f.key);
        StringBuilder hint = new StringBuilder(f.hint);
        if (f.options != null) {
            hint.append("（可选值：").append(TextUtils.join(" / ", f.options)).append("）");
        }
        new FrostDialog(act)
                .title("修改" + f.key)
                .hint(hint.toString())
                .input(current, "passwd".equals(f.key))
                .positive("保存", dialog -> {
                    String value = dialog.text().trim();
                    String error = f.validate(value);
                    if (error != null) {
                        FloatToast.show(act, error);
                        return false;
                    }
                    c.set(f.key, value);
                    if (!c.save()) {
                        FloatToast.show(act, "写入配置文件失败");
                        return false;
                    }
                    refreshValues();
                    FloatToast.show(act, "重启服务后生效");
                    if ("theme".equals(f.key)) {
                        act.recreateAfterThemeChange();
                    }
                    return true;
                })
                .negative("取消", null)
                .show();
    }
}
