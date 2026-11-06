package com.clockdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/** 简单的设置读写封装（全部存 SharedPreferences） */
public final class Prefs {

    public static final String K_URL = "server_url";
    public static final String K_TOKEN = "token";
    public static final String K_CITY = "city";
    public static final String K_LAT = "lat";
    public static final String K_LON = "lon";
    public static final String K_NIGHT_START = "night_start";
    public static final String K_NIGHT_END = "night_end";
    public static final String K_NIGHT_ONLY = "night_only";
    public static final String K_SOUND = "remind_sound";
    public static final String K_TODO_MIN = "todos_min";
    public static final String K_WX_MIN = "weather_min";
    public static final String K_NOTIFIED = "notified_keys";
    public static final String K_REV = "config_rev";
    public static final String K_TODO_REV = "todo_rev";
    public static final String K_SCHEDULED = "scheduled_alarms";
    public static final String K_STAY_ON = "stay_on_plugged";
    public static final String K_AUTO_BOOT = "auto_boot_open";
    /** 最后一次收到任意闹钟广播的时间：界面据此判断后台闹钟是否已停摆并接管同步 */
    public static final String K_LAST_ALARM = "last_alarm_ms";
    /** 最后一次过期自愈尝试的时间：断网时给所有调用方共享退避，避免反复超时重连 */
    public static final String K_LAST_HEAL = "last_heal_ms";
    /** 最后一次弹过的服务器留言 id：同一留言只弹一次（服务器最多重发 3 次） */
    public static final String K_LAST_NOTICE = "last_notice_id";

    private static final String NAME = "clockdash";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void put(Context c, String key, String value) {
        // apply() 异步写盘（API 9+），避免 commit() 同步刷盘在老设备主线程上卡顿
        sp(c).edit().putString(key, value == null ? "" : value).apply();
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static void putBool(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }

    public static long getLong(Context c, String key, long def) {
        return sp(c).getLong(key, def);
    }

    public static void putLong(Context c, String key, long value) {
        sp(c).edit().putLong(key, value).apply();
    }

    public static int getInt(Context c, String key, int def) {
        try {
            return Integer.parseInt(sp(c).getString(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    /** 提醒去重：记录已提醒过的 key（occ 时间戳），超过 120 条后丢弃最旧的 */
    public static boolean isNotified(Context c, String key) {
        String s = sp(c).getString(K_NOTIFIED, "");
        return s.contains("|" + key + "|");
    }

    public static void addNotified(Context c, String key) {
        SharedPreferences p = sp(c);
        String s = p.getString(K_NOTIFIED, "");
        if (s.contains("|" + key + "|")) return;
        if (!s.startsWith("|")) s = "|" + s; // 保证首尾都有分隔符，否则第一条永远匹配不上
        s = s + key + "|";
        String[] parts = s.split("\\|");
        if (parts.length > 120) {
            StringBuilder b = new StringBuilder("|");
            for (int i = parts.length - 120; i < parts.length; i++) {
                if (parts[i].length() > 0) b.append(parts[i]).append("|");
            }
            s = b.toString();
        }
        p.edit().putString(K_NOTIFIED, s).apply();
    }

    /** 修改系统“充电时保持唤醒”设置（STAY_ON_WHILE_PLUGGED_IN = AC | USB） */
    public static boolean applyStayOn(Context c, boolean on) {
        try {
            Settings.System.putInt(c.getContentResolver(),
                    Settings.System.STAY_ON_WHILE_PLUGGED_IN, on ? 3 : 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
