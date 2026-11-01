import com.clockdash.Recur;
import com.clockdash.Todo;

import java.util.ArrayList;
import java.util.Calendar;

/** Recur.expand 的独立单元测试：javac RecurTest.java && java RecurTest（不依赖 Android） */
public class RecurTest {

    private static int passed = 0;

    private static long cal(int y, int m, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    private static Todo todo(String repeat, String date, String time) {
        Todo t = new Todo();
        t.id = "x";
        t.title = "test";
        t.repeat = repeat;
        t.date = date;
        t.time = time;
        t.remind = 0;
        t.interval = 1;
        return t;
    }

    private static void eq(String name, Object expect, Object actual) {
        if (!expect.equals(actual)) {
            throw new AssertionError(name + " 期望 " + expect + " 实际 " + actual);
        }
        passed++;
        System.out.println("  ok - " + name);
    }

    /** 把发生时刻毫秒转成 “yyyy-MM-dd HH:mm” 便于断言 */
    private static String fmt(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format("%04d-%02d-%02d %02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    public static void main(String[] args) {
        // 1. 不重复：范围内 1 次，范围外 0 次
        Todo t1 = todo("none", "2026-11-05", "09:00");
        ArrayList<Long> r = Recur.expand(t1, cal(2026, 11, 1, 0, 0), cal(2026, 11, 30, 23, 59));
        eq("none 命中 1 次", 1, r.size());
        eq("none 时间正确", "2026-11-05 09:00", fmt(r.get(0)));
        eq("none 范围外为 0", 0, Recur.expand(t1, cal(2026, 12, 1, 0, 0), cal(2026, 12, 31, 0, 0)).size());

        // 2. 每两天：11-01 08:00 起，1/3/5/7
        Todo t2 = todo("daily", "2026-11-01", "08:00");
        t2.interval = 2;
        r = Recur.expand(t2, cal(2026, 11, 1, 0, 0), cal(2026, 11, 7, 23, 59));
        eq("daily 每2天共 4 次", 4, r.size());
        eq("daily 首次", "2026-11-01 08:00", fmt(r.get(0)));
        eq("daily 末次", "2026-11-07 08:00", fmt(r.get(3)));

        // 3. 每周[周三,周五]：锚点是周二 11-03，同周更早的匹配日（11-02 周一不属于集合，11-01 周日）不得早于锚点出现
        Todo t3 = todo("weekly", "2026-11-03", "19:00"); // 2026-11-03 是周二
        t3.weekdays = new int[]{2, 5}; // 周二、周五
        r = Recur.expand(t3, cal(2026, 11, 1, 0, 0), cal(2026, 11, 8, 23, 59));
        eq("weekly 命中 2 次", 2, r.size());
        eq("weekly 首次=锚点周二", "2026-11-03 19:00", fmt(r.get(0)));
        eq("weekly 次次=周五", "2026-11-06 19:00", fmt(r.get(1)));

        // 4. 每两周（跟随锚点星期）：11-04 与 11-18（均为周三）
        Todo t4 = todo("weekly", "2026-11-04", "10:00");
        t4.interval = 2;
        r = Recur.expand(t4, cal(2026, 11, 1, 0, 0), cal(2026, 11, 30, 23, 59));
        eq("weekly 每2周共 2 次", 2, r.size());
        eq("weekly 第1次", "2026-11-04 10:00", fmt(r.get(0)));
        eq("weekly 第2次", "2026-11-18 10:00", fmt(r.get(1)));

        // 5. 每月 31 号：2 月（28天）、4 月（30天）跳过
        Todo t5 = todo("monthly", "2026-01-31", "09:00");
        r = Recur.expand(t5, cal(2026, 1, 1, 0, 0), cal(2026, 4, 30, 23, 59));
        eq("monthly 31号 命中 2 次(2/4月跳过)", 2, r.size());
        eq("monthly 1月31", "2026-01-31 09:00", fmt(r.get(0)));
        eq("monthly 3月31", "2026-03-31 09:00", fmt(r.get(1)));

        // 6. 每年 2月29：仅闰年
        Todo t6 = todo("yearly", "2024-02-29", "08:30");
        r = Recur.expand(t6, cal(2024, 1, 1, 0, 0), cal(2029, 12, 31, 23, 59));
        eq("yearly 2/29 仅闰年 2 次", 2, r.size());
        eq("yearly 2024", "2024-02-29 08:30", fmt(r.get(0)));
        eq("yearly 2028", "2028-02-29 08:30", fmt(r.get(1)));

        // 7. 每2小时 09:00-21:00（分钟取 time 的 30 分）：9:30,11:30,...,21:30 共 7 次
        Todo t7 = todo("hourly", "2026-11-01", "09:30");
        t7.interval = 2;
        t7.windowFrom = "09:00";
        t7.windowTo = "21:00";
        r = Recur.expand(t7, cal(2026, 11, 1, 0, 0), cal(2026, 11, 1, 23, 59));
        eq("hourly 一天 7 次", 7, r.size());
        eq("hourly 首次", "2026-11-01 09:30", fmt(r.get(0)));
        eq("hourly 末次", "2026-11-01 21:30", fmt(r.get(6)));

        // 8. 结束日期：每天循环到 11-03 为止
        Todo t8 = todo("daily", "2026-11-01", "07:00");
        t8.end = "2026-11-03";
        r = Recur.expand(t8, cal(2026, 11, 1, 0, 0), cal(2026, 11, 10, 23, 59));
        eq("end 截止共 3 次", 3, r.size());
        eq("end 末次", "2026-11-03 07:00", fmt(r.get(2)));

        // 9. 循环展开上限保护（300）
        Todo t9 = todo("daily", "2000-01-01", "00:00");
        r = Recur.expand(t9, cal(2026, 1, 1, 0, 0), cal(2030, 1, 1, 0, 0));
        eq("CAP 上限生效", Recur.CAP, r.size());

        // 10. 范围起点晚于锚点：只取范围内的（从 11-05 开始查每天循环）
        Todo t10 = todo("daily", "2026-11-01", "06:00");
        r = Recur.expand(t10, cal(2026, 11, 5, 0, 0), cal(2026, 11, 6, 23, 59));
        eq("中途查询命中 2 次", 2, r.size());
        eq("中途查询首日", "2026-11-05 06:00", fmt(r.get(0)));

        System.out.println("\n全部通过：" + passed + " 项断言");
    }
}
