package com.esurfing.client.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.esurfing.client.App;
import com.esurfing.client.core.Config;

/** 开机 / 应用更新后自动拉起认证服务（配置里 auto_start=true 且账号已填才启动）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot) {
            return;
        }
        Context app = context.getApplicationContext();
        Config cfg = Config.load(App.get() != null
                ? App.get().configFile()
                : new java.io.File(app.getFilesDir(), "esurfing.conf"));
        // 开机自启是应用级设置，单独存放
        if (!com.esurfing.client.AppPrefs.autoStart(app) || !cfg.ready()) {
            return;
        }
        AuthService.start(app, AuthService.ACTION_START);
        KeepAliveJobService.schedule(app);
    }
}
