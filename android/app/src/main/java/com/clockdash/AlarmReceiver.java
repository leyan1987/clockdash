package com.clockdash;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONObject;

/** 闹钟与同步的接收器 */
public class AlarmReceiver extends BroadcastReceiver {

    private static PowerManager.WakeLock sLock;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (AlarmScheduler.ACTION_FIRE.equals(action)) {
            String title = intent.getStringExtra("title");
            long occ = intent.getLongExtra("occ", 0);
            boolean sound = intent.getBooleanExtra("sound", true);
            String key = intent.getStringExtra("key");
            if (key != null) Prefs.addNotified(context, key);
            Intent ri = new Intent(context, ReminderActivity.class);
            ri.putExtra("title", title);
            ri.putExtra("occ", occ);
            ri.putExtra("sound", sound);
            ri.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            boolean popupOk = true;
            try {
                context.startActivity(ri);
            } catch (Exception e) {
                // 现代 Android 限制后台拉起界面：改发带铃声的强提醒通知
                popupOk = false;
            }
            // 弹窗成功 → 通知静音（铃声由提醒界面负责）；弹窗失败 → 通知改走强提醒
            boolean notifSound = !popupOk && sound;
            postNotification(context, title, occ, notifSound, popupOk);
            return;
        }

        if (AlarmScheduler.ACTION_ROLLOVER.equals(action)) {
            // 跨天：重排提醒（周期闹钟本身会自动续）；含文件 IO，走后台队列防 ANR
            AlarmScheduler.scheduleRemindersAsync(context);
            return;
        }

        if (AlarmScheduler.ACTION_CHECK.equals(action)) {
            // 轻量推送检查：只拉版本号，有变化才做完整同步
            acquireLock(context);
            final Context app = context.getApplicationContext();
            new AsyncTask<Void, Void, Boolean>() {
                @Override
                protected Boolean doInBackground(Void... params) {
                    String base = Store.serverBase(app);
                    if (base == null) return Boolean.FALSE;
                    String s = HttpUtil.get(base + "/api/check",
                            Prefs.get(app, Prefs.K_TOKEN, ""), 8000);
                    if (s == null) return Boolean.FALSE;
                    try {
                        JSONObject o = new JSONObject(s);
                        if (!o.optBoolean("ok", false)) return Boolean.FALSE;
                        boolean changed = o.optInt("todo_rev", 0) != Prefs.getInt(app, Prefs.K_TODO_REV, 0)
                                || o.optInt("config_rev", 0) != Prefs.getInt(app, Prefs.K_REV, 0);
                        if (!changed) return Boolean.FALSE;
                        Store.fetchTodos(app);
                        Store.fetchConfigPush(app);
                        Store.fetchHolidays(app);
                        // 配置变化可能换了城市/位置，顺带刷新天气，免去等下一个天气闹钟
                        Store.fetchWeather(app);
                        return Boolean.TRUE;
                    } catch (Exception e) {
                        return Boolean.FALSE;
                    }
                }

                @Override
                protected void onPostExecute(Boolean changed) {
                    try {
                        if (changed) AlarmScheduler.schedulePeriodic(app);
                    } finally {
                        releaseLock();
                    }
                }
            }.execute();
            return;
        }

        // SYNC / WX：后台拉取 + 重排
        acquireLock(context);
        final boolean wantTodos = AlarmScheduler.ACTION_SYNC.equals(action);
        final Context app = context.getApplicationContext();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                if (wantTodos) {
                    Store.fetchTodos(app);
                    Store.fetchConfigPush(app);
                    Store.fetchHolidays(app);
                } else {
                    Store.fetchWeather(app);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                try {
                    if (wantTodos) {
                        // 推送配置可能改了刷新间隔，整体重排
                        AlarmScheduler.schedulePeriodic(app);
                    } else {
                        AlarmScheduler.scheduleRemindersAsync(app);
                    }
                } finally {
                    releaseLock();
                }
            }
        }.execute();
    }
    private void postNotification(Context c, String title, long occ, boolean sound, boolean popupOk) {
        try {
            AlarmScheduler.ensureChannel(c);
            Intent ri = new Intent(c, ReminderActivity.class);
            ri.putExtra("title", title);
            ri.putExtra("occ", occ);
            ri.putExtra("sound", sound);
            int id = AlarmScheduler.reqCode(occ, title);
            PendingIntent pi = PendingIntent.getActivity(c, id, ri, AlarmScheduler.piFlags());
            boolean loud = !popupOk && sound; // 弹窗被拦时改用带铃声的强提醒渠道
            // 注意：Notification.Builder 的双参构造是 API 26 才有的，老系统必须走单参构造，
            // 否则抛 NoSuchMethodError（Error 不被 catch(Exception) 捕获）直接崩进程
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, loud
                        ? AlarmScheduler.CHANNEL_ID_LOUD : AlarmScheduler.CHANNEL_ID);
            } else {
                b = new Notification.Builder(c);
            }
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_HIGH);
                if (loud) {
                    b.setSound(android.media.RingtoneManager
                            .getDefaultUri(android.media.RingtoneManager.TYPE_ALARM));
                    b.setVibrate(new long[]{0, 400, 400, 400, 400, 800});
                }
            }
            b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm);
            b.setWhen(System.currentTimeMillis());
            b.setAutoCancel(true);
            b.setContentTitle("日程提醒");
            b.setContentText(title == null ? "" : title);
            b.setContentIntent(pi);
            if (Build.VERSION.SDK_INT >= 29) {
                // 现代 Android 禁止后台拉起界面：用全屏意图弹出提醒界面
                b.setFullScreenIntent(pi, true);
            }
            Notification n = Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
            if (n != null) {
                ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE))
                        .notify(id, n);
            }
        } catch (Throwable ignored) {
            // 用 Throwable 兜底：老设备碎片化严重，避免通知环节把进程带崩
        }
    }

    private static void acquireLock(Context c) {
        if (sLock == null) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            sLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clockdash:sync");
            sLock.setReferenceCounted(false);
        }
        // acquire(long) 是 API 17（Android 4.2）才有的，4.0.3 上会 NoSuchMethodError
        if (Build.VERSION.SDK_INT >= 17) {
            sLock.acquire(60000);
        } else {
            sLock.acquire();
        }
    }

    private static void releaseLock() {
        if (sLock != null && sLock.isHeld()) {
            try {
                sLock.release();
            } catch (Exception ignored) {
            }
        }
    }
}
