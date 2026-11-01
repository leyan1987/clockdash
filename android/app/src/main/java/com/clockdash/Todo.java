package com.clockdash;

/** 一条待办规则（POJO，字段与服务器 todos.json 对应） */
public class Todo {

    public String id;
    public String title;
    /** 开始日期 yyyy-MM-dd */
    public String date;
    /** 时间 HH:mm */
    public String time;
    /** 提前提醒分钟数；-1 不提醒；0 准时 */
    public int remind;
    /** none | hourly | daily | weekly | monthly | yearly */
    public String repeat;
    /** 每 N 小时/天/周/月/年（重复时 >= 1） */
    public int interval = 1;
    /** weekly 用：1=周一 … 7=周日；为空表示跟随开始日期的星期 */
    public int[] weekdays;
    /** hourly 用：生效时段 */
    public String windowFrom;
    public String windowTo;
    /** 结束日期 yyyy-MM-dd，可空 */
    public String end;

    public boolean reminds() {
        return remind >= 0;
    }
}
