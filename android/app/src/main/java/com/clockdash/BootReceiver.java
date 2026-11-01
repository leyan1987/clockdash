package com.clockdash;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后：恢复所有闹钟；若开启“开机自启”则延迟几秒自动打开看板 */
public class BootReceiver extends BroadcastReceiver {

    private static final String ACTION_LAUNCH = "com.clockdash.action.LAUNCH";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_LAUNCH.equals(action)) {
            // 系统就绪后拉起看板（老系统允许从后台启动 Activity）
            Intent i = new Intent(context, DashboardActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(i);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AlarmScheduler.schedulePeriodic(context);
            if (Prefs.getBool(context, Prefs.K_AUTO_BOOT, true)) {
                // 延迟 4 秒，等系统桌面/存储就绪后再打开看板
                PendingIntent pi = PendingIntent.getBroadcast(context, 101,
                        new Intent(context, BootReceiver.class).setAction(ACTION_LAUNCH), 0);
                ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE))
                        .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 4000L, pi);
            }
        }
    }
}
