package com.clockdash;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;

/**
 * 循环日程展开引擎（纯 Java，不依赖 Android，便于单独测试）。
 *
 * 语义（全部按平板本地时区）：
 *  none    在 date+time 发生一次
 *  daily   从 date 起每 interval 天，在 time 发生
 *  weekly  interval=1 且 weekdays 非空：每周中命中的星期几发生；
 *          weekdays 为空：跟随 date 的星期，每 interval 周
 *  monthly 每 interval 月的 date 日（该月没有此日则跳过）
 *  yearly  每 interval 年的 date（2月29日不存在则跳过）
 *  hourly  每天 window_from..window_to 之间，从 window_from 起每 interval 小时，
 *          分钟取 time 的分钟；未填窗口默认 08:00-22:00
 *  end     结束日期，发生的日期必须 <= end
 */
public final class Recur {

    /** 单次展开的条数上限，保护老设备 */
    public static final int CAP = 300;
    private static final int CAP_DAYS = 500;

    private Recur() {}

    /** 展开 [fromMs, toMs] 内的所有发生时刻（毫秒，本地时区），返回升序列表 */
    public static ArrayList<Long> expand(Todo t, long fromMs, long toMs) {
        ArrayList<Long> out = new ArrayList<Long>();
        if (t == null || t.date == null || t.date.length() != 10
                || t.time == null || t.time.length() < 4) {
            return out;
        }
        Calendar from = Calendar.getInstance();
        from.setTimeInMillis(fromMs);
        zeroTime(from);
        Calendar to = Calendar.getInstance();
        to.setTimeInMillis(toMs);

        Calendar anchor = Calendar.getInstance();
        if (!setDate(anchor, t.date)) return out;

        Calendar endLimit = null; // end 当天 24:00
        if (t.end != null && t.end.length() == 10) {
            endLimit = Calendar.getInstance();
            if (setDate(endLimit, t.end)) endLimit.add(Calendar.DAY_OF_MONTH, 1);
            else endLimit = null;
        }

        String rep = t.repeat == null ? "none" : t.repeat;
        int step = Math.max(1, t.interval <= 0 ? 1 : t.interval);

        if ("none".equals(rep)) {
            addIfInRange(out, anchor, t, from, to, endLimit);

        } else if ("hourly".equals(rep)) {
            int h0 = parsePart(t.windowFrom == null ? "08:00" : t.windowFrom, true);
            int h1 = parsePart(t.windowTo == null ? "22:00" : t.windowTo, true);
            if (h1 < h0) h1 = h0;
            Calendar day = (Calendar) anchor.clone();
            if (day.before(from)) day.setTimeInMillis(from.getTimeInMillis());
            zeroTime(day);
            for (int guard = 0; guard < CAP_DAYS && !day.after(to) && out.size() < CAP; guard++) {
                for (int h = h0; h <= h1 && out.size() < CAP; h += step) {
                    Calendar occ = (Calendar) day.clone();
                    occ.set(Calendar.HOUR_OF_DAY, h);
                    occ.set(Calendar.MINUTE, parsePart(t.time, false));
                    // 注意：不能用 addIfInRange，它会把小时覆盖回 t.time 的小时
                    checkAndAdd(out, occ, from, to, endLimit);
                }
                day.add(Calendar.DAY_OF_MONTH, 1);
            }

        } else if ("daily".equals(rep)) {
            Calendar c = (Calendar) anchor.clone();
            while (c.before(from)) c.add(Calendar.DAY_OF_MONTH, step);
            for (int guard = 0; guard < CAP_DAYS && !c.after(to) && out.size() < CAP; guard++) {
                if (!addIfInRange(out, c, t, from, to, endLimit)) break;
                c.add(Calendar.DAY_OF_MONTH, step);
            }

        } else if ("weekly".equals(rep)) {
            if (t.weekdays == null || t.weekdays.length == 0) {
                Calendar c = (Calendar) anchor.clone();
                while (c.before(from)) c.add(Calendar.DAY_OF_MONTH, 7 * step);
                for (int guard = 0; guard < CAP_DAYS && !c.after(to) && out.size() < CAP; guard++) {
                    if (!addIfInRange(out, c, t, from, to, endLimit)) break;
                    c.add(Calendar.DAY_OF_MONTH, 7 * step);
                }
            } else {
                Calendar c = (Calendar) from.clone();
                if (c.before(anchor)) c.setTimeInMillis(anchor.getTimeInMillis());
                long anchorWeek = weekIndex(anchor);
                for (int guard = 0; guard < CAP_DAYS && !c.after(to) && out.size() < CAP; guard++) {
                    long w = weekIndex(c);
                    if (w >= anchorWeek && (w - anchorWeek) % step == 0
                            && !c.before(anchor) && contains(t.weekdays, dowMon1(c))) {
                        addIfInRange(out, c, t, from, to, endLimit);
                    }
                    c.add(Calendar.DAY_OF_MONTH, 1);
                }
            }

        } else if ("monthly".equals(rep)) {
            int day = anchor.get(Calendar.DAY_OF_MONTH);
            int y0 = anchor.get(Calendar.YEAR), m0 = anchor.get(Calendar.MONTH);
            for (int k = 0; k < 12 * 20 && out.size() < CAP; k++) {
                int m = m0 + k * step;
                int y = y0 + m / 12;
                m = m % 12;
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(y, m, 1, 0, 0, 0);
                if (c.getTimeInMillis() > to.getTimeInMillis() + 31L * 86400000L) break;
                if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH)) continue;
                c.set(Calendar.DAY_OF_MONTH, day);
                if (c.before(anchor)) continue;
                if (!addIfInRange(out, c, t, from, to, endLimit)) {
                    if (c.after(to)) break;
                }
            }

        } else if ("yearly".equals(rep)) {
            int day = anchor.get(Calendar.DAY_OF_MONTH);
            int month = anchor.get(Calendar.MONTH);
            int y0 = anchor.get(Calendar.YEAR);
            for (int k = 0; k < 60 && out.size() < CAP; k++) {
                int y = y0 + k * step;
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(y, month, 1, 0, 0, 0);
                if (c.getTimeInMillis() > to.getTimeInMillis() + 366L * 86400000L) break;
                if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH)) continue;
                c.set(Calendar.DAY_OF_MONTH, day);
                if (c.before(anchor)) continue;
                if (!addIfInRange(out, c, t, from, to, endLimit)) {
                    if (c.after(to)) break;
                }
            }
        }

        Collections.sort(out);
        return out;
    }

    /** 按规则的 time 在 day 上构造发生时刻，检查后加入；到达 end 时返回 false（用于提前终止） */
    private static boolean addIfInRange(ArrayList<Long> out, Calendar day, Todo t,
                                        Calendar from, Calendar to, Calendar endLimit) {
        Calendar occ = (Calendar) day.clone();
        occ.set(Calendar.HOUR_OF_DAY, parsePart(t.time, true));
        occ.set(Calendar.MINUTE, parsePart(t.time, false));
        return checkAndAdd(out, occ, from, to, endLimit);
    }

    /** 对已构造好时刻的发生做范围/end 检查后加入；返回 false 表示已到 end 且在范围内（调用方可提前终止） */
    private static boolean checkAndAdd(ArrayList<Long> out, Calendar occ,
                                       Calendar from, Calendar to, Calendar endLimit) {
        occ.set(Calendar.SECOND, 0);
        occ.set(Calendar.MILLISECOND, 0);
        if (occ.before(from) || occ.after(to)) return true;
        if (endLimit != null && !occ.before(endLimit)) return false;
        out.add(Long.valueOf(occ.getTimeInMillis()));
        return true;
    }

    /** 把日历设为某天的 00:00:00.000；解析失败返回 false */
    private static boolean setDate(Calendar c, String date) {
        try {
            String[] p = date.split("-");
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    /** kind=true 解析小时，false 解析分钟 */
    private static int parsePart(String time, boolean hour) {
        try {
            String[] p = time.split(":");
            int v = Integer.parseInt(p[hour ? 0 : 1]);
            return hour ? clamp(v, 0, 23) : clamp(v, 0, 59);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 周一=1 … 周日=7 */
    private static int dowMon1(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK); // SUNDAY=1..SATURDAY=7
        return d == 1 ? 7 : d - 1;
    }

    private static long weekIndex(Calendar c) {
        // 以 1970-01-05（周一）为第 0 周
        long days = (c.getTimeInMillis() + tzOffset(c)) / 86400000L;
        return (days - 4) / 7;
    }

    private static long tzOffset(Calendar c) {
        return c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET);
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }
}
