package com.clockdash;

/** 农历锚点校验：用已知的春节/中秋/端午/除夕公历日期逐年验证 Lunar 算法与数据表 */
public class LunarTest {

    private static int passed = 0;

    private static void eq(String name, String expect, String actual) {
        if (!expect.equals(actual)) {
            throw new AssertionError(name + " 期望 " + expect + " 实际 " + actual);
        }
        passed++;
        System.out.println("  ok - " + name + " = " + actual);
    }

    private static void checkSpring(int y, String mmd) {
        String[] p = mmd.split("-");
        int[] l = Lunar.solarToLunar(y, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        if (l[1] != 1 || l[2] != 1) {
            throw new AssertionError(y + " 春节 " + mmd + " 算得 农历" + l[0] + "-" + l[1] + "-" + l[2]);
        }
        passed++;
        System.out.println("  ok - " + y + " 春节 " + mmd);
    }

    public static void main(String[] args) {
        // 1900-01-31 应为农历正月初一（算法基准日）
        int[] base = Lunar.solarToLunar(1900, 1, 31);
        if (base[1] != 1 || base[2] != 1) throw new AssertionError("基准日错误");
        passed++;

        // 春节锚点 2000-2031（历年国务院公布日期，公开常识）
        checkSpring(2000, "2-5");   checkSpring(2001, "1-24");  checkSpring(2002, "2-12");
        checkSpring(2003, "2-1");   checkSpring(2004, "1-22");  checkSpring(2005, "2-9");
        checkSpring(2006, "1-29");  checkSpring(2007, "2-18");  checkSpring(2008, "2-7");
        checkSpring(2009, "1-26");  checkSpring(2010, "2-14");  checkSpring(2011, "2-3");
        checkSpring(2012, "1-23");  checkSpring(2013, "2-10");  checkSpring(2014, "1-31");
        checkSpring(2015, "2-19");  checkSpring(2016, "2-8");   checkSpring(2017, "1-28");
        checkSpring(2018, "2-16");  checkSpring(2019, "2-5");   checkSpring(2020, "1-25");
        checkSpring(2021, "2-12");  checkSpring(2022, "2-1");   checkSpring(2023, "1-22");
        checkSpring(2024, "2-10");  checkSpring(2025, "1-29");  checkSpring(2026, "2-17");
        checkSpring(2027, "2-6");   checkSpring(2028, "1-26");  checkSpring(2029, "2-13");
        checkSpring(2030, "2-3");   checkSpring(2031, "1-23");

        // 中秋（八月十五）
        checkLunar(2024, 9, 17, "中秋");
        checkLunar(2025, 10, 6, "中秋");
        checkLunar(2026, 9, 25, "中秋");
        // 端午
        checkLunar(2026, 6, 19, "端午");
        // 除夕（春节前一天）
        checkLunar(2026, 2, 16, "除夕");
        // 闰月显示：2020 闰四月，闰四月初一标签应为“闰四月”
        checkLabel(2020, 5, 23, "闰四月"); // 2020-05-23 = 闰四月初一
        // 普通日：2026-11-01 的农历日为“廿二”级文字，仅验证非节日且能算出
        String ld = Lunar.label(2026, 11, 1);
        if (ld == null || ld.length() == 0) throw new AssertionError("2026-11-01 农历为空");
        passed++;
        System.out.println("  ok - 2026-11-01 农历 = " + ld);

        // 农历日名回归：tens 数组曾错位导致 廿一→十一、十三→初三
        String[] dayCases = {
            "初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
            "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
            "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"};
        for (int d = 1; d <= 30; d++) {
            String got = Lunar.chinaDay(d);
            if (!dayCases[d - 1].equals(got))
                throw new AssertionError("chinaDay(" + d + ") 期望 " + dayCases[d - 1] + " 实际 " + got);
            passed++;
        }
        System.out.println("  ok - 农历日名 1-30 全部正确");

        System.out.println("\n全部通过：" + passed + " 项锚点");
    }

    private static void checkLunar(int y, int m, int d, String name) {
        String label = Lunar.label(y, m, d);
        if (!name.equals(label)) {
            throw new AssertionError(y + "-" + m + "-" + d + " 期望 " + name + " 实际 " + label);
        }
        passed++;
        System.out.println("  ok - " + y + "-" + m + "-" + d + " = " + label);
    }

    private static void checkLabel(int y, int m, int d, String expect) {
        String label = Lunar.label(y, m, d);
        if (!expect.equals(label)) {
            throw new AssertionError(y + "-" + m + "-" + d + " 期望 " + expect + " 实际 " + label);
        }
        passed++;
        System.out.println("  ok - " + y + "-" + m + "-" + d + " = " + label);
    }
}
