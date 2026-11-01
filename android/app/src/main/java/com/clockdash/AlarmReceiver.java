package com.clockdash;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
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
            postNotification(context, title, occ, sound);
            Intent ri = new Intent(context, ReminderActivity.class);
            ri.putExtra("title", title);
            ri.putExtra("occ", occ);
            ri.putExtra("sound", sound);
            ri.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            context.startActivity(ri);
            return;
        }

        if (AlarmScheduler.ACTION_ROLLOVER.equals(action)) {
            // 跨天：重排提醒（周期闹钟本身会自动续）
            AlarmScheduler.scheduleReminders(context);
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
                        AlarmScheduler.scheduleReminders(app);
                    }
                } finally {
                    releaseLock();
                }
            }
        }.execute();
    }

    private void postNotification(Context c, String title, long occ, boolean sound) {
        try {
            Intent ri = new Intent(c, ReminderActivity.class);
            ri.putExtra("title", title);
            ri.putExtra("occ", occ);
            ri.putExtra("sound", sound);
            int id = AlarmScheduler.reqCode(occ, title);
            PendingIntent pi = PendingIntent.getActivity(c, id, ri, 0);
            Notification.Builder b = new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm);
            b.setWhen(System.currentTimeMillis());
            b.setAutoCancel(true);
            b.setContentTitle("日程提醒");
            b.setContentText(title == null ? "" : title);
            b.setContentIntent(pi);
            // 声音由弹出的提醒界面负责，这里保持静音避免重复响铃
            Notification n = b.getNotification();
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
        } catch (Exception ignored) {
        }
    }

    private static void acquireLock(Context c) {
        if (sLock == null) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            sLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clockdash:sync");
            sLock.setReferenceCounted(false);
        }
        sLock.acquire(60000);
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
