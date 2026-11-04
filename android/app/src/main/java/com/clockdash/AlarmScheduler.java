package com.clockdash;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 闹钟调度：周期同步 + 未来 24 小时的提醒 */
public final class AlarmScheduler {

    public static final String ACTION_FIRE = "com.clockdash.action.FIRE";
    public static final String ACTION_SYNC = "com.clockdash.action.SYNC";
    public static final String ACTION_WX = "com.clockdash.action.WX";
    public static final String ACTION_ROLLOVER = "com.clockdash.action.ROLLOVER";
    public static final String ACTION_CHECK = "com.clockdash.action.CHECK";

    private static final int REQ_SYNC = 1;
    private static final int REQ_WX = 2;
    private static final int REQ_ROLLOVER = 3;
    private static final int REQ_CHECK = 4;
    private static final int MAX_REMINDERS = 60;
    /** 错过提醒的补救窗口：到点后 5 分钟内同步到的，立即补弹提醒 */
    private static final long LATE_GRACE = 5 * 60000L;

    private AlarmScheduler() {}

    /** 通知渠道（Android 8.0+ 必需；老系统自动走旧路径） */
    public static final String CHANNEL_ID = "clockdash_alarms";
    /** 弹窗被系统拦截时的强提醒渠道（带铃声与震动） */
    public static final String CHANNEL_ID_LOUD = "clockdash_alarms_loud";

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "日程提醒",
                    NotificationManager.IMPORTANCE_HIGH));
            // 弹窗被拦时的强提醒渠道：随通知播放闹钟铃声并震动
            NotificationChannel loud = new NotificationChannel(CHANNEL_ID_LOUD, "日程提醒（强提醒）",
                    NotificationManager.IMPORTANCE_HIGH);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build();
            loud.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs);
            loud.enableVibration(true);
            nm.createNotificationChannel(loud);
        }
    }

    /** PendingIntent 不可变标志：Android 12 (API 31) 起必须显式声明 */
    public static int piFlags() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    /** 精确闹钟兼容：Android 12+ 用户可能收回精确闹钟权限，此时退化为近似闹钟 */
    private static void setExactCompat(Context c, AlarmManager am, long triggerAt, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    private static PendingIntent op(Context c, String action, int req) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(action);
        return PendingIntent.getBroadcast(c, req, i, piFlags());
    }

    /**
     * 提醒的 PendingIntent/通知 id。若只用发生时间做 id，同一分钟的两条待办会互相顶掉
     * （Intent.filterEquals 不比较 extras），混入标题哈希保证唯一且可重排覆盖。
     */
    public static int reqCode(long occ, String title) {
        return (int) ((occ / 60000L) ^ (long) (title == null ? 0 : title.hashCode()));
    }

    private static AlarmManager am(Context c) {
        return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    }

    /** 设置周期闹钟（开机 / 设置变更 / 打开应用时调用） */
    public static void schedulePeriodic(Context c) {
        long now = System.currentTimeMillis();
        int todoMin = Math.max(1, Prefs.getInt(c, Prefs.K_TODO_MIN, 60));
        int wxMin = Math.max(5, Prefs.getInt(c, Prefs.K_WX_MIN, 60));
        am(c).setRepeating(AlarmManager.RTC_WAKEUP, now + 4000,
                todoMin * 60000L, op(c, ACTION_SYNC, REQ_SYNC));
        am(c).setRepeating(AlarmManager.RTC_WAKEUP, now + 15000,
                wxMin * 60000L, op(c, ACTION_WX, REQ_WX));
        am(c).setRepeating(AlarmManager.RTC_WAKEUP, nextMidnight() + 120000L,
                86400000L, op(c, ACTION_ROLLOVER, REQ_ROLLOVER));
        // 轻量推送检查：每分钟一个几十字节的版本号请求，后台一保存约 1 分钟内就同步
        am(c).setRepeating(AlarmManager.RTC_WAKEUP, now + 30000,
                60000L, op(c, ACTION_CHECK, REQ_CHECK));
        scheduleReminders(c);
    }

    /**
     * 把未来 24 小时内的所有提醒注册为精确闹钟（4.0.3 上 set() 即精确）。
     * 先取消上一轮注册的闹钟再重排：删除/修改待办后，旧提醒才不会残留误响。
     */
    public static void scheduleReminders(Context c) {
        AlarmManager am = am(c);
        cancelScheduled(c, am);
        List<Todo> todos = Store.loadTodosCache(c);
        long now = System.currentTimeMillis();
        long horizon = now + 24L * 3600000L;
        JSONArray codes = new JSONArray();
        int count = 0;
        for (int i = 0; i < todos.size() && count < MAX_REMINDERS; i++) {
            Todo t = todos.get(i);
            if (!t.reminds()) continue;
            long early = now + t.remind * 60000L; // 让“刚过头的”也能对上
            ArrayList<Long> occs = Recur.expand(t, early, horizon);
            for (int k = 0; k < occs.size() && count < MAX_REMINDERS; k++) {
                long occ = occs.get(k).longValue();
                long fireAt = occ - t.remind * 60000L;
                if (fireAt < now - LATE_GRACE) continue; // 超过补救窗口的错过不再提醒
                String key = t.id + ":" + occ;
                if (Prefs.isNotified(c, key)) continue;
                int code = reqCode(occ, t.title);
                Intent intent = new Intent(c, AlarmReceiver.class);
                intent.setAction(ACTION_FIRE);
                intent.putExtra("title", t.title);
                intent.putExtra("occ", occ);
                intent.putExtra("key", key);
                intent.putExtra("sound", Prefs.getBool(c, Prefs.K_SOUND, true));
                PendingIntent pi = PendingIntent.getBroadcast(c, code, intent, piFlags());
                if (fireAt <= now + 10000) {
                    // 提醒时刻已过但还在补救窗口内（如刚同步到的临期待办）：
                    // 不能简单跳过，否则待办到点“无声无息消失”。延后 2 秒立即补提醒。
                    am(c).set(AlarmManager.RTC_WAKEUP, now + 2000, pi);
                } else {
                    setExactCompat(c, am(c), fireAt, pi);
                }
                codes.put(code);
                count++;
            }
        }
        Prefs.put(c, Prefs.K_SCHEDULED, codes.toString());
    }

    /** 取消上一轮注册到系统的提醒闹钟（记录的是 requestCode 列表） */
    private static void cancelScheduled(Context c, AlarmManager am) {
        String s = Prefs.get(c, Prefs.K_SCHEDULED, "");
        if (s == null || s.length() < 2) return;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                int code = arr.optInt(i, -1);
                if (code < 0) continue;
                Intent intent = new Intent(c, AlarmReceiver.class);
                intent.setAction(ACTION_FIRE);
                PendingIntent pi = PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_NO_CREATE);
                if (pi != null) am.cancel(pi);
            }
        } catch (Exception ignored) {
        }
    }

    /** 稍后再提醒 */
    public static void snooze(Context c, String title, long delayMs, boolean sound) {
        long at = System.currentTimeMillis() + delayMs;
        Intent intent = new Intent(c, AlarmReceiver.class);
        intent.setAction(ACTION_FIRE);
        intent.putExtra("title", title);
        intent.putExtra("occ", at);
        intent.putExtra("key", "snooze:" + at);
        intent.putExtra("sound", sound);
        PendingIntent pi = PendingIntent.getBroadcast(c, reqCode(at, title), intent, piFlags());
        setExactCompat(c, am(c), at, pi);
    }

    private static long nextMidnight() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
