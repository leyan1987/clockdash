package com.clockdash;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

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

    private AlarmScheduler() {}

    private static PendingIntent op(Context c, String action, int req) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(action);
        return PendingIntent.getBroadcast(c, req, i, 0);
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

    /** 把未来 24 小时内的所有提醒注册为精确闹钟（4.0.3 上 set() 即精确） */
    public static void scheduleReminders(Context c) {
        List<Todo> todos = Store.loadTodosCache(c);
        long now = System.currentTimeMillis();
        long horizon = now + 24L * 3600000L;
        int count = 0;
        for (int i = 0; i < todos.size() && count < MAX_REMINDERS; i++) {
            Todo t = todos.get(i);
            if (!t.reminds()) continue;
            long early = now + t.remind * 60000L; // 让“刚过头的”也能对上
            ArrayList<Long> occs = Recur.expand(t, early, horizon);
            for (int k = 0; k < occs.size() && count < MAX_REMINDERS; k++) {
                long occ = occs.get(k).longValue();
                long fireAt = occ - t.remind * 60000L;
                if (fireAt <= now + 10000) continue;
                String key = t.id + ":" + occ;
                if (Prefs.isNotified(c, key)) continue;
                Intent intent = new Intent(c, AlarmReceiver.class);
                intent.setAction(ACTION_FIRE);
                intent.putExtra("title", t.title);
                intent.putExtra("occ", occ);
                intent.putExtra("key", key);
                intent.putExtra("sound", Prefs.getBool(c, Prefs.K_SOUND, true));
                PendingIntent pi = PendingIntent.getBroadcast(c, reqCode(occ, t.title), intent, 0);
                am(c).set(AlarmManager.RTC_WAKEUP, fireAt, pi);
                count++;
            }
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
        PendingIntent pi = PendingIntent.getBroadcast(c, reqCode(at, title), intent, 0);
        am(c).set(AlarmManager.RTC_WAKEUP, at, pi);
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
