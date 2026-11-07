package com.clockdash;

import java.util.Calendar;

/**
 * 中国农历（1900-2049，经典 lunarInfo 数据表，取自 solarlunar/calendar.js 同源数据）。
 * 纯 Java 无 Android 依赖；数据表经春节/中秋等锚点日期逐年校验。
 */
public final class Lunar {

    private static final int[] LUNAR_INFO = {
        0x04bd8,0x04ae0,0x0a570,0x054d5,0x0d260,0x0d950,0x16554,0x056a0,0x09ad0,0x055d2,0x04ae0,0x0a5b6,0x0a4d0,0x0d250,0x1d255,0x0b540,0x0d6a0,0x0ada2,0x095b0,0x14977,0x04970,0x0a4b0,0x0b4b5,0x06a50,0x06d40,0x1ab54,0x02b60,0x09570,0x052f2,0x04970,0x06566,0x0d4a0,0x0ea50,0x06e95,0x05ad0,0x02b60,0x186e3,0x092e0,0x1c8d7,0x0c950,0x0d4a0,0x1d8a6,0x0b550,0x056a0,0x1a5b4,0x025d0,0x092d0,0x0d2b2,0x0a950,0x0b557,0x06ca0,0x0b550,0x15355,0x04da0,0x0a5b0,0x14573,0x052b0,0x0a9a8,0x0e950,0x06aa0,0x0aea6,0x0ab50,0x04b60,0x0aae4,0x0a570,0x05260,0x0f263,0x0d950,0x05b57,0x056a0,0x096d0,0x04dd5,0x04ad0,0x0a4d0,0x0d4d4,0x0d250,0x0d558,0x0b540,0x0b6a0,0x195a6,0x095b0,0x049b0,0x0a974,0x0a4b0,0x0b27a,0x06a50,0x06d40,0x0af46,0x0ab60,0x09570,0x04af5,0x04970,0x064b0,0x074a3,0x0ea50,0x06b58,0x05ac0,0x0ab60,0x096d5,0x092e0,0x0c960,0x0d954,0x0d4a0,0x0da50,0x07552,0x056a0,0x0abb7,0x025d0,0x092d0,0x0cab5,0x0a950,0x0b4a0,0x0baa4,0x0ad50,0x055d9,0x04ba0,0x0a5b0,0x15176,0x052b0,0x0a930,0x07954,0x06aa0,0x0ad50,0x05b52,0x04b60,0x0a6e6,0x0a4e0,0x0d260,0x0ea65,0x0d530,0x05aa0,0x076a3,0x096d0,0x04afb,0x04ad0,0x0a4d0,0x1d0b6,0x0d250,0x0d520,0x0dd45,0x0b5a0,0x056d0,0x055b2,0x049b0,0x0a577,0x0a4b0,0x0aa50,0x1b255,0x06d20,0x0ada0
    };

    private static final int BASE_YEAR = 1900;

    private static final String[] MONTH_NAMES = {
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    };

    private static final String[] DAY_DIGITS = {
        "日", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"
    };

    private Lunar() {
    }

    /** 该农历年的闰月月份（0 = 无闰月） */
    public static int leapMonth(int y) {
        return LUNAR_INFO[y - BASE_YEAR] & 0xF;
    }

    /** 农历年 y 的 m 月天数（29/30），m=1..12 */
    public static int monthDays(int y, int m) {
        return (LUNAR_INFO[y - BASE_YEAR] & (0x10000 >> m)) == 0 ? 29 : 30;
    }

    public static int leapDays(int y) {
        if (leapMonth(y) == 0) return 0;
        return (LUNAR_INFO[y - BASE_YEAR] & 0x10000) != 0 ? 30 : 29;
    }

    public static int yearDays(int y) {
        int sum = 348;
        for (int i = 0x8000; i > 0x8; i >>= 1) {
            sum += (LUNAR_INFO[y - BASE_YEAR] & i) == 0 ? 0 : 1;
        }
        return sum + leapDays(y);
    }

    /** 农历日中文名：初一…初九、初十、十一…十九、二十、廿一…廿九、三十 */
    public static String chinaDay(int d) {
        if (d == 10) return "初十";
        if (d == 20) return "二十";
        if (d == 30) return "三十";
        String[] tens = {"初", "十", "廿", "卅"};
        return tens[d / 10] + DAY_DIGITS[d % 10];
    }

    /** 公历转农历，返回 {年, 月, 日}；闰月用负月份数表示；超出支持范围返回 null */
    public static int[] solarToLunar(int y, int m, int d) {
        Calendar base = Calendar.getInstance();
        base.clear();
        base.set(BASE_YEAR, 0, 31); // 1900-01-31 = 农历正月初一
        Calendar cur = Calendar.getInstance();
        cur.clear();
        cur.set(y, m - 1, d);
        long offset = (cur.getTimeInMillis() - base.getTimeInMillis()) / 86400000L;
        if (offset < 0) return null;

        int year = BASE_YEAR;
        int days = yearDays(year);
        while (offset >= days) {
            offset -= days;
            year++;
            if (year >= BASE_YEAR + LUNAR_INFO.length) return null;
            days = yearDays(year);
        }

        int leap = leapMonth(year);
        boolean isLeap = false;
        int i;
        int temp = 0;
        for (i = 1; i < 13 && offset > 0; i++) {
            if (leap > 0 && i == leap + 1 && !isLeap) {
                --i;
                isLeap = true;
                temp = leapDays(year);
            } else {
                temp = monthDays(year, i);
            }
            if (isLeap && i == leap + 1) {
                isLeap = false;
            }
            offset -= temp;
        }
        if (offset == 0 && leap > 0 && i == leap + 1) {
            if (isLeap) {
                isLeap = false;
            } else {
                isLeap = true;
                --i;
            }
        }
        if (offset < 0) {
            offset += temp;
            --i;
        }
        int month = i;
        int day = (int) (offset + 1);
        if (isLeap) month = -month;
        return new int[]{year, month, day};
    }

    /** 农历节日（不含公历节日） */
    private static String festival(int ly, int lm, int ld) {
        if (lm == 1 && ld == 1) return "春节";
        if (lm == 1 && ld == 15) return "元宵";
        if (lm == 5 && ld == 5) return "端午";
        if (lm == 7 && ld == 7) return "七夕";
        if (lm == 8 && ld == 15) return "中秋";
        if (lm == 9 && ld == 9) return "重阳";
        if (lm == 12 && ld == 8) return "腊八";
        if (lm == 12 && ld == monthDays(ly, 12)) return "除夕";
        return null;
    }

    /**
     * 日历格上的农历短标签：
     * 农历每月初一显示月名（含“闰”），农历节日显示节日名，其余显示农历日（如“廿二”）。
     * 超出数据范围返回空串。
     */
    public static String label(int y, int m, int d) {
        int[] l = solarToLunar(y, m, d);
        if (l == null) return "";
        int ly = l[0], lm = l[1], ld = l[2];
        boolean isLeap = lm < 0;
        if (isLeap) lm = -lm;
        if (!isLeap) {
            String f = festival(ly, lm, ld);
            if (f != null) return f;
        }
        if (ld == 1) return (isLeap ? "闰" : "") + MONTH_NAMES[lm - 1];
        return chinaDay(ld);
    }
}
