package com.esurfing.client.service;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.esurfing.client.App;
import com.esurfing.client.core.Config;

/**
 * 看门狗任务：每 15 分钟看一眼认证服务还在不在，被厂商 ROM 清掉就重新拉起。
 *
 * <p>JobScheduler 的任务跨重启保留（setPersisted），是前台服务之外的第二道保险。</p>
 */
public class KeepAliveJobService extends JobService {

    private static final int JOB_ID = 2001;
    private static final long PERIOD = 15 * 60 * 1000L;

    public static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, KeepAliveJobService.class))
                .setPeriodic(PERIOD)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
        try {
            js.schedule(b.build());
        } catch (RuntimeException ignored) {
            // 部分 ROM 限制任务数量，忽略
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!AuthService.isRunning()) {
            Config cfg = Config.load(App.get() != null
                    ? App.get().configFile()
                    : new java.io.File(getFilesDir(), "esurfing.conf"));
            if (cfg.ready() && cfg.boolOf("auto_start", true)) {
                try {
                    startForegroundService(new Intent(this, AuthService.class)
                            .setAction(AuthService.ACTION_START));
                } catch (RuntimeException ignored) {
                    // 后台启动前台服务可能被系统拒绝，等下一次
                }
            }
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
