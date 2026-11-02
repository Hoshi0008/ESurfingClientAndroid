package com.esurfing.client.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import com.esurfing.client.App;
import com.esurfing.client.core.Config;

/**
 * 配色方案。所有界面颜色都从这里取，保证浅色 / 深色两套主题各自协调，
 * 并且文本与背景的对比度足够（正文 #101828 / #EAF0FA，弱化文字也保持 4.5:1 以上）。
 */
public final class Theme {

    public final boolean dark;

    public final int bg1, bg2, bg3;
    public final int glow1, glow2;
    public final int txt, sub, muted;
    public final int card, cardStroke, cardShadow;
    public final int accent1, accent2, accentSoft;
    public final int red, green, yellow, onYellow, onAccent;
    public final int bar, barStroke;
    public final int chipBg, chipTxt;
    public final int divider;
    public final int inputBg, inputStroke, inputHint;
    public final int logBg, logTxt, logTime;
    public final int ok, info, warn, step;
    public final int scrim, toastBg, toastTxt;
    public final int dialogCard, dialogStroke;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg1 = Color.parseColor("#0A0F18");
            bg2 = Color.parseColor("#0D1524");
            bg3 = Color.parseColor("#0F1A2B");
            glow1 = 0x6B295CFF;
            glow2 = 0x4C0FA3A3;
            txt = Color.parseColor("#EAF0FA");
            sub = Color.parseColor("#A5B1C6");
            muted = Color.parseColor("#7C89A0");
            card = 0x9E17202F;
            cardStroke = 0x17FFFFFF;
            cardShadow = 0x66000000;
            accent1 = Color.parseColor("#4D8BFF");
            accent2 = Color.parseColor("#22C7E8");
            accentSoft = 0x1F4D8BFF;
            red = Color.parseColor("#FF6B6E");
            green = Color.parseColor("#34D399");
            yellow = Color.parseColor("#FFD34E");
            onYellow = Color.parseColor("#241C00");
            onAccent = Color.WHITE;
            bar = 0xB8121A28;
            barStroke = 0x14FFFFFF;
            chipBg = 0x294D8BFF;
            chipTxt = Color.parseColor("#8FB5FF");
            divider = 0x14FFFFFF;
            inputBg = 0xB30B111C;
            inputStroke = 0x1FFFFFFF;
            inputHint = Color.parseColor("#6E7B93");
            logBg = 0xD9060A11;
            logTxt = Color.parseColor("#CBD8EC");
            logTime = 0x8FCBD8EC;
            ok = Color.parseColor("#45D99F");
            info = Color.parseColor("#7FB2FF");
            warn = Color.parseColor("#FFCF5C");
            step = Color.parseColor("#B39CFF");
            scrim = 0x47000000;
            toastBg = 0xE6101622;
            toastTxt = Color.parseColor("#F2F6FF");
            dialogCard = 0xCC1C2637;
            dialogStroke = 0x1AFFFFFF;
        } else {
            bg1 = Color.parseColor("#EEF4FD");
            bg2 = Color.parseColor("#E3EBF8");
            bg3 = Color.parseColor("#D7E2F4");
            glow1 = 0x8C689CFF;
            glow2 = 0x6161E2D0;
            txt = Color.parseColor("#101828");
            sub = Color.parseColor("#55607A");
            muted = Color.parseColor("#6F7A91");
            card = 0xA9FFFFFF;
            cardStroke = 0xEBFFFFFF;
            cardShadow = 0x1F1F3866;
            accent1 = Color.parseColor("#1F6BFF");
            accent2 = Color.parseColor("#12B8E8");
            accentSoft = 0x1F1F6BFF;
            red = Color.parseColor("#D92D32");
            green = Color.parseColor("#0E7C56");
            yellow = Color.parseColor("#F7C948");
            onYellow = Color.parseColor("#3D2F00");
            onAccent = Color.WHITE;
            bar = 0xB8FFFFFF;
            barStroke = 0xF2FFFFFF;
            chipBg = 0x1A1F6BFF;
            chipTxt = Color.parseColor("#1A5BE0");
            divider = 0x14101828;
            inputBg = 0xD1FFFFFF;
            inputStroke = 0x1A101828;
            inputHint = Color.parseColor("#8B95AA");
            logBg = 0xEB0D1421;
            logTxt = Color.parseColor("#D6E2F5");
            logTime = 0x8FD6E2F5;
            ok = Color.parseColor("#34C88A");
            info = Color.parseColor("#6FA8FF");
            warn = Color.parseColor("#FFC94D");
            step = Color.parseColor("#A493FF");
            scrim = 0x3D081220;
            toastBg = 0xF0101622;
            toastTxt = Color.parseColor("#F2F6FF");
            dialogCard = 0xC7FFFFFF;
            dialogStroke = 0xF2FFFFFF;
        }
    }

    public static Theme of(Context ctx) {
        return new Theme(isDark(ctx));
    }

    /** 配置里的 theme=auto/light/dark，auto 时跟随系统。 */
    public static boolean isDark(Context ctx) {
        String mode = "auto";
        try {
            Config c = App.get().config();
            if (c != null) {
                mode = c.get("theme");
            }
        } catch (RuntimeException ignored) {
            // Application 还没起来时按系统走
        }
        if ("light".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("dark".equalsIgnoreCase(mode)) {
            return true;
        }
        int night = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }
}
