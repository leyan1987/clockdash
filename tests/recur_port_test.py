# -*- coding: utf-8 -*-
"""Recur.expand（Java）的 1:1 Python 移植 + 与 Java 版同款的 10 组用例。
逐行对照 android/.../Recur.java 移植，验证循环算法语义本身。
运行: python tests/recur_port_test.py
"""
from datetime import datetime, timedelta

CAP = 300
CAP_DAYS = 500
EPOCH = datetime(1970, 1, 1)


class Todo:
    def __init__(self):
        self.id = "x"; self.title = "test"
        self.date = ""; self.time = "00:00"; self.remind = 0
        self.repeat = "none"; self.interval = 1
        self.weekdays = None; self.windowFrom = None; self.windowTo = None
        self.end = None


def set_date(c, date):
    try:
        p = date.split("-")
        return c.replace(year=int(p[0]), month=int(p[1]), day=int(p[2]),
                         hour=0, minute=0, second=0, microsecond=0)
    except Exception:
        return None


def parse_part(time, hour):
    try:
        v = int(time.split(":")[0 if hour else 1])
    except Exception:
        return 0
    return max(0, min(v, 23 if hour else 59))


def dow_mon1(c):  # 周一=1..周日=7
    return c.weekday() + 1


def week_index(c):  # 1970-01-05(周一) 为第 0 周
    days = (c - EPOCH).days
    return (days - 4) // 7


def check_and_add(out, occ, frm, to, end_limit):
    occ = occ.replace(second=0, microsecond=0)
    if occ < frm or occ > to:
        return True
    if end_limit is not None and occ >= end_limit:
        return False
    out.append(occ)
    return True


def add_if_in_range(out, day, t, frm, to, end_limit):
    occ = day.replace(hour=parse_part(t.time, True), minute=parse_part(t.time, False),
                      second=0, microsecond=0)
    return check_and_add(out, occ, frm, to, end_limit)


def expand(t, frm, to):
    out = []
    if not t.date or len(t.date) != 10 or not t.time or len(t.time) < 4:
        return out
    anchor = set_date(datetime.now(), t.date)
    if anchor is None:
        return out
    end_limit = None
    if t.end and len(t.end) == 10:
        e = set_date(datetime.now(), t.end)
        if e is not None:
            end_limit = e + timedelta(days=1)
    rep = t.repeat or "none"
    step = max(1, t.interval if t.interval and t.interval > 0 else 1)

    if rep == "none":
        add_if_in_range(out, anchor, t, frm, to, end_limit)

    elif rep == "hourly":
        h0 = parse_part(t.windowFrom or "08:00", True)
        h1 = parse_part(t.windowTo or "22:00", True)
        if h1 < h0:
            h1 = h0
        day = max(anchor, frm.replace(hour=0, minute=0, second=0, microsecond=0))
        for guard in range(CAP_DAYS):
            if day > to or len(out) >= CAP:
                break
            for h in range(h0, h1 + 1, step):
                if len(out) >= CAP:
                    break
                occ = day.replace(hour=h, minute=parse_part(t.time, False),
                                  second=0, microsecond=0)
                if occ < anchor:
                    continue
                # 注意：不能用 add_if_in_range，它会把小时覆盖回 t.time 的小时
                check_and_add(out, occ, frm, to, end_limit)
            day += timedelta(days=1)

    elif rep == "daily":
        c = anchor
        while c < frm:
            c += timedelta(days=step)
        for guard in range(CAP_DAYS):
            if c > to or len(out) >= CAP:
                break
            if not add_if_in_range(out, c, t, frm, to, end_limit):
                break
            c += timedelta(days=step)

    elif rep == "weekly":
        if not t.weekdays:
            c = anchor
            while c < frm:
                c += timedelta(days=7 * step)
            for guard in range(CAP_DAYS):
                if c > to or len(out) >= CAP:
                    break
                if not add_if_in_range(out, c, t, frm, to, end_limit):
                    break
                c += timedelta(days=7 * step)
        else:
            c = max(frm, anchor)
            aw = week_index(anchor)
            for guard in range(CAP_DAYS):
                if c > to or len(out) >= CAP:
                    break
                w = week_index(c)
                if w >= aw and (w - aw) % step == 0 and c >= anchor and dow_mon1(c) in t.weekdays:
                    add_if_in_range(out, c, t, frm, to, end_limit)
                c += timedelta(days=1)

    elif rep == "monthly":
        day = anchor.day
        y0, m0 = anchor.year, anchor.month
        for k in range(12 * 20):
            if len(out) >= CAP:
                break
            m = m0 + k * step
            y, m = y0 + m // 12, m % 12
            if m == 0:
                y, m = y - 1, 12
            first = datetime(y, m, 1)
            if first > to + timedelta(days=31):
                break
            if day > calendar_month_max(y, m):
                continue
            c = first.replace(day=day)
            if c < anchor:
                continue
            if not add_if_in_range(out, c, t, frm, to, end_limit) and c > to:
                break

    elif rep == "yearly":
        day, month, y0 = anchor.day, anchor.month, anchor.year
        for k in range(60):
            if len(out) >= CAP:
                break
            y = y0 + k * step
            first = datetime(y, month, 1)
            if first > to + timedelta(days=366):
                break
            if day > calendar_month_max(y, month):
                continue
            c = first.replace(day=day)
            if c < anchor:
                continue
            if not add_if_in_range(out, c, t, frm, to, end_limit) and c > to:
                break

    out.sort()
    return out


def calendar_month_max(y, m):
    if m == 12:
        nxt = datetime(y + 1, 1, 1)
    else:
        nxt = datetime(y, m + 1, 1)
    return (nxt - timedelta(days=1)).day


# ---------- 与 Java 版 RecurTest 相同的 10 组用例 ----------
passed = 0


def eq(name, expect, actual):
    global passed
    assert expect == actual, "%s 期望 %s 实际 %s" % (name, expect, actual)
    passed += 1
    print("  ok -", name)


def dt(s):
    return datetime.strptime(s, "%Y-%m-%d %H:%M")


def todo(repeat, date, time_):
    t = Todo()
    t.repeat, t.date, t.time = repeat, date, time_
    return t


eq("none 命中 1 次", 1, len(expand(todo("none", "2026-11-05", "09:00"), dt("2026-11-01 00:00"), dt("2026-11-30 23:59"))))
eq("none 时间正确", [dt("2026-11-05 09:00")], expand(todo("none", "2026-11-05", "09:00"), dt("2026-11-01 00:00"), dt("2026-11-30 23:59")))
eq("none 范围外为 0", 0, len(expand(todo("none", "2026-11-05", "09:00"), dt("2026-12-01 00:00"), dt("2026-12-31 00:00"))))

t2 = todo("daily", "2026-11-01", "08:00"); t2.interval = 2
r = expand(t2, dt("2026-11-01 00:00"), dt("2026-11-07 23:59"))
eq("daily 每2天共 4 次", 4, len(r))
eq("daily 首次", dt("2026-11-01 08:00"), r[0])
eq("daily 末次", dt("2026-11-07 08:00"), r[3])

t3 = todo("weekly", "2026-11-03", "19:00"); t3.weekdays = [2, 5]  # 锚点周二
r = expand(t3, dt("2026-11-01 00:00"), dt("2026-11-08 23:59"))
eq("weekly 命中 2 次", 2, len(r))
eq("weekly 首次=锚点周二", dt("2026-11-03 19:00"), r[0])
eq("weekly 次次=周五", dt("2026-11-06 19:00"), r[1])

t4 = todo("weekly", "2026-11-04", "10:00"); t4.interval = 2
r = expand(t4, dt("2026-11-01 00:00"), dt("2026-11-30 23:59"))
eq("weekly 每2周共 2 次", 2, len(r))
eq("weekly 第1次", dt("2026-11-04 10:00"), r[0])
eq("weekly 第2次", dt("2026-11-18 10:00"), r[1])

r = expand(todo("monthly", "2026-01-31", "09:00"), dt("2026-01-01 00:00"), dt("2026-04-30 23:59"))
eq("monthly 31号 命中 2 次(2/4月跳过)", 2, len(r))
eq("monthly 1月31", dt("2026-01-31 09:00"), r[0])
eq("monthly 3月31", dt("2026-03-31 09:00"), r[1])

r = expand(todo("yearly", "2024-02-29", "08:30"), dt("2024-01-01 00:00"), dt("2029-12-31 23:59"))
eq("yearly 2/29 仅闰年 2 次", 2, len(r))
eq("yearly 2024", dt("2024-02-29 08:30"), r[0])
eq("yearly 2028", dt("2028-02-29 08:30"), r[1])

t7 = todo("hourly", "2026-11-01", "09:30"); t7.interval = 2
t7.windowFrom, t7.windowTo = "09:00", "21:00"
r = expand(t7, dt("2026-11-01 00:00"), dt("2026-11-01 23:59"))
eq("hourly 一天 7 次", 7, len(r))
eq("hourly 首次", dt("2026-11-01 09:30"), r[0])
eq("hourly 末次", dt("2026-11-01 21:30"), r[6])

t8 = todo("daily", "2026-11-01", "07:00"); t8.end = "2026-11-03"
r = expand(t8, dt("2026-11-01 00:00"), dt("2026-11-10 23:59"))
eq("end 截止共 3 次", 3, len(r))
eq("end 末次", dt("2026-11-03 07:00"), r[2])

r = expand(todo("daily", "2000-01-01", "00:00"), dt("2026-01-01 00:00"), dt("2030-01-01 00:00"))
eq("CAP 上限生效", CAP, len(r))

r = expand(todo("daily", "2026-11-01", "06:00"), dt("2026-11-05 00:00"), dt("2026-11-06 23:59"))
eq("中途查询命中 2 次", 2, len(r))
eq("中途查询首日", dt("2026-11-05 06:00"), r[0])

print("\n全部通过：%d 项断言（Recur 算法语义验证）" % passed)
