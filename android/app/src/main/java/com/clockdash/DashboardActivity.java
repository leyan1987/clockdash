package com.clockdash;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 主界面（方案A 三栏）：左=时钟+天气，中=月历，右=待办。
 * 性能原则：每秒只在文本变化时 setText；日历跨天重排；列表在数据变化时重绑。
 */
public class DashboardActivity extends Activity {

    private static final String TAG = "ClockDash";
    private static final String[] WD = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
    private static final SimpleDateFormat HM_FMT = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat DAY_KEY_FMT = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat YMD_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private TextView tvClock, tvDate, tvTemp, tvCond, tvCity, tvMeta, tvStatus, tvSyncNote;
    private TextView tvCalTitle, tvCalYear;
    private TextView[] tvFcDay = new TextView[3];
    private TextView[] tvFcTxt = new TextView[3];
    private WeatherIconView icNow;
    private WeatherIconView[] icFc = new WeatherIconView[3];
    private TextView[] wdViews = new TextView[7];
    private LinearLayout colMid, colRight, boxWx, calGrid;
    private View rowFc;
    private ListView listTodos;

    private final TextView[] cellText = new TextView[42];
    private final View[] cellDot = new View[42];

    private List<Todo> mTodos = new ArrayList<Todo>();
    private WeatherInfo mWx;
    private long mLastWxMtime = 0;
    /** 法定节假日：date -> 节日名；调休补班：date -> 节日名 */
    private final HashMap<String, String> mOffDays = new HashMap<String, String>();
    private final HashMap<String, String> mBanDays = new HashMap<String, String>();
    private final ArrayList<String[]> mRows = new ArrayList<String[]>(); // {when, title, today}
    private final TodoAdapter mAdapter = new TodoAdapter();

    private String mDayKey = "";
    private long mLastTodosMtime = 0;
    private int mTickCount = 99;
    private boolean mNightIn = false;
    private boolean mNightOnly = false;
    private int mCornerTaps = 0;
    private long mLastCornerTap = 0;
    private boolean mSyncing = false;
    private String mVersion = "";
    /** 运行时权限引导：一次启动只走一遍完整检查 */
    private boolean mPermPassDone = false;
    /** 通知权限已询问过一次：避免用户拒绝后反复弹系统框（也防止卡在第一步） */
    private boolean mNotifAsked = false;
    /** 最近一次待办列表重排的时间（诊断用，显示在状态栏） */
    private String mListBuiltAt = "";

    private final Handler mHandler = new Handler();

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            try {
                Calendar now = Calendar.getInstance();
                String hm = HM_FMT.format(now.getTime());
                if (!hm.contentEquals(tvClock.getText())) {
                    tvClock.setText(hm);
                    try {
                        tvDate.setText(dateLine(now));
                    } catch (Exception e) {
                        Log.e(TAG, "dateLine", e);
                    }
                    String key = DAY_KEY_FMT.format(now.getTime());
                    if (!key.equals(mDayKey)) {
                        mDayKey = key;
                        try {
                            refreshAll(false);
                            // 离线（无服务器数据）时也必须跨天重绘日历和列表
                            buildCalendar(now);
                            // 跨天后预报的“明天/后天”标签也要重算
                            renderWeather();
                        } catch (Exception e) {
                            Log.e(TAG, "day-rollover", e);
                        }
                    }
                    // 每分钟重排待办列表：条目过点后自动跳到下一次发生
                    try {
                        buildTodoRows();
                    } catch (Exception e) {
                        Log.e(TAG, "buildTodoRows", e);
                    }
                    mTickCount = 0;
                }
                if (++mTickCount % 30 == 0) {
                    try {
                        applyNight(now);
                        refreshAll(false);
                        buildTodoRows(); // 双保险：即使分钟分支异常也每 30 秒重排一次
                    } catch (Exception e) {
                        Log.e(TAG, "tick30", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "tick", e);
            }
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                // 开机自启动时直接盖过滑动锁屏（对安全锁无效）
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        tvClock = (TextView) findViewById(R.id.tvClock);
        tvDate = (TextView) findViewById(R.id.tvDate);
        tvTemp = (TextView) findViewById(R.id.tvTemp);
        tvCond = (TextView) findViewById(R.id.tvCond);
        tvCity = (TextView) findViewById(R.id.tvCity);
        tvMeta = (TextView) findViewById(R.id.tvMeta);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvSyncNote = (TextView) findViewById(R.id.tvSyncNote);
        tvCalTitle = (TextView) findViewById(R.id.tvCalTitle);
        tvCalYear = (TextView) findViewById(R.id.tvCalYear);
        colMid = (LinearLayout) findViewById(R.id.colMid);
        colRight = (LinearLayout) findViewById(R.id.colRight);
        boxWx = (LinearLayout) findViewById(R.id.boxWx);
        rowFc = findViewById(R.id.rowFc);
        calGrid = (LinearLayout) findViewById(R.id.calGrid);
        listTodos = (ListView) findViewById(R.id.listTodos);

        tvFcDay[0] = (TextView) findViewById(R.id.tvFcDay0);
        tvFcDay[1] = (TextView) findViewById(R.id.tvFcDay1);
        tvFcDay[2] = (TextView) findViewById(R.id.tvFcDay2);
        tvFcTxt[0] = (TextView) findViewById(R.id.tvFcTxt0);
        tvFcTxt[1] = (TextView) findViewById(R.id.tvFcTxt1);
        tvFcTxt[2] = (TextView) findViewById(R.id.tvFcTxt2);
        icNow = (WeatherIconView) findViewById(R.id.icNow);
        icFc[0] = (WeatherIconView) findViewById(R.id.icFc0);
        icFc[1] = (WeatherIconView) findViewById(R.id.icFc1);
        icFc[2] = (WeatherIconView) findViewById(R.id.icFc2);

        int[] wdIds = {R.id.wd0, R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6};
        for (int i = 0; i < 7; i++) {
            wdViews[i] = (TextView) findViewById(wdIds[i]);
            wdViews[i].setTextColor(i >= 5 ? getResources().getColor(R.color.red) : getResources().getColor(R.color.muted));
        }

        buildCalendarPool();
        listTodos.setAdapter(mAdapter);

        // 触摸不灵敏的保底入口：右上角连点 3 次 / 时钟长按 / 菜单键
        findViewById(R.id.root).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    int w = v.getWidth(), h = v.getHeight();
                    if (event.getX() > w * 0.8f && event.getY() < h * 0.2f) {
                        long now = System.currentTimeMillis();
                        mCornerTaps = (now - mLastCornerTap < 2000) ? mCornerTaps + 1 : 1;
                        mLastCornerTap = now;
                        if (mCornerTaps >= 3) {
                            mCornerTaps = 0;
                            openSettings();
                        }
                    }
                }
                return false;
            }
        });
        tvClock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openSettings();
                return true;
            }
        });

        // 充电常亮（系统级兜底）
        Prefs.applyStayOn(this, Prefs.getBool(this, Prefs.K_STAY_ON, true));

        try {
            mVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            mVersion = "?";
        }
    }

    /** 依次检查并引导运行时/特殊权限（现代 Android；老系统全部跳过） */
    private void runPermissionPass() {
        if (mPermPassDone) return;
        // 1) 通知权限（Android 13+）：提醒可见性
        if (Build.VERSION.SDK_INT >= 33 && !mNotifAsked
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            mNotifAsked = true; // 只问一次，拒绝后继续后续检查，不再反复弹
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            return; // 等授权回调后继续下一项
        }
        // 2) 精确闹钟（Android 12+）：被用户收回时引导去系统设置
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                mPermPassDone = true;
                new AlertDialog.Builder(this)
                        .setTitle("需要“闹钟和提醒”权限")
                        .setMessage("待办的准时提醒依赖精确闹钟。请点击“去设置”，"
                                + "在本应用的“闹钟和提醒”页面选择“允许”。")
                        .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.fromParts("package", getPackageName(), null)));
                            }
                        })
                        .setNegativeButton("暂不", null)
                        .show();
                return;
            }
        }
        // 3) 全屏意图（Android 14+）：被收回时提醒只能以普通通知出现
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                mPermPassDone = true;
                new AlertDialog.Builder(this)
                        .setTitle("需要“全屏意图”权限")
                        .setMessage("到点提醒需要全屏弹出。请点击“去设置”，"
                                + "在本应用的通知设置里允许“全屏意图”。")
                        .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                startActivity(new Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.fromParts("package", getPackageName(), null)));
                            }
                        })
                        .setNegativeButton("暂不", null)
                        .show();
                return;
            }
        }
        // 4) 修改系统设置（Android 6+）：充电常亮的系统级兜底
        if (Build.VERSION.SDK_INT >= 23 && Prefs.getBool(this, Prefs.K_STAY_ON, true)
                && !Settings.System.canWrite(this)) {
            mPermPassDone = true;
            new AlertDialog.Builder(this)
                    .setTitle("需要“修改系统设置”权限")
                    .setMessage("“充电时屏幕永不休眠”依赖该权限。请点击“去设置”并允许。")
                    .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.fromParts("package", getPackageName(), null)));
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
            return;
        }
        mPermPassDone = true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            // 无论授予与否，继续剩余的权限检查
            runPermissionPass();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mDayKey = "";
        refreshAll(true); // 先按缓存/空数据渲染一遍，避免首次进入或服务器不通时界面空白
        mHandler.post(mTick);
        syncNow();
        AlarmScheduler.schedulePeriodic(this);
        runPermissionPass(); // 现代 Android 的运行时/特殊权限引导（每轮启动一次）
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- 数据 ----------

    /** 拉取服务器数据（后台）并刷新界面 */
    private void syncNow() {
        if (mSyncing || Store.serverBase(this) == null) {
            updateStatus();
            return;
        }
        mSyncing = true;
        final DashboardActivity activity = this;
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                boolean ok = Store.fetchTodos(activity) != null;
                Store.fetchWeather(activity);
                boolean pushed = Store.fetchConfigPush(activity);
                Store.fetchHolidays(activity);
                return ok || pushed ? Boolean.TRUE : Boolean.FALSE;
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                mSyncing = false;
                refreshAll(true);
                AlarmScheduler.scheduleReminders(activity);
            }
        }.execute();
    }

    /** 从缓存重读并重绘（sync 后 force=true，平时在待办或天气缓存变化时重绘） */
    private void refreshAll(boolean force) {
        long mtime = Store.todosCacheTime(this);
        long wmtime = Store.weatherCacheTime(this);
        if (force || (mtime != 0 && mtime != mLastTodosMtime)
                || (wmtime != 0 && wmtime != mLastWxMtime)) {
            mLastTodosMtime = mtime;
            mLastWxMtime = wmtime;
            mTodos = Store.loadTodosCache(this);
            mWx = Store.loadWeatherCache(this);
            Store.loadHolidays(this, mOffDays, mBanDays);
            renderWeather();
            buildTodoRows();
            buildCalendar(Calendar.getInstance());
            AlarmScheduler.scheduleReminders(this);
        }
        updateStatus();
    }

    private void updateStatus() {
        long t = Store.todosCacheTime(this);
        String extra = " · 列于 " + (mListBuiltAt.length() == 0 ? "--:--" : mListBuiltAt)
                + " · v" + mVersion;
        if (Store.serverBase(this) == null) {
            tvStatus.setText("未配置服务器：连点右上角三次进入设置" + extra);
            tvStatus.setTextColor(getResources().getColor(R.color.amber));
            return;
        }
        if (t == 0) {
            tvStatus.setText("正在连接服务器…" + extra);
            tvStatus.setTextColor(getResources().getColor(R.color.dim));
            return;
        }
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t));
        boolean ok = System.currentTimeMillis() - t < 2L * 3600000L;
        tvStatus.setText("同步于 " + time + (ok ? " · 服务器正常" : " · 连接失败，显示缓存") + extra);
        tvStatus.setTextColor(ok ? getResources().getColor(R.color.dim) : getResources().getColor(R.color.red));
    }

    // ---------- 时钟 / 夜间 ----------

    private String dateLine(Calendar c) {
        String s = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 "
                + WD[c.get(Calendar.DAY_OF_WEEK) - 1] + " · " + c.get(Calendar.YEAR);
        if (!mOffDays.isEmpty()) {
            String hn = mOffDays.get(YMD_FMT.format(c.getTime()));
            if (hn != null && hn.length() > 0) s += " · " + hn;
        }
        return s;
    }

    private static int minutes(String hhmm, int def) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return def;
        }
    }

    private void applyNight(Calendar now) {
        int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int s = minutes(Prefs.get(this, Prefs.K_NIGHT_START, "22:30"), 22 * 60 + 30);
        int e = minutes(Prefs.get(this, Prefs.K_NIGHT_END, "06:30"), 6 * 60 + 30);
        boolean in = s <= e ? (cur >= s && cur < e) : (cur >= s || cur < e);
        boolean only = in && Prefs.getBool(this, Prefs.K_NIGHT_ONLY, false);

        if (in != mNightIn) {
            mNightIn = in;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = in ? 0.06f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        }
        if (only != mNightOnly) {
            mNightOnly = only;
            int vis = only ? View.GONE : View.VISIBLE;
            colMid.setVisibility(vis);
            colRight.setVisibility(vis);
            boxWx.setVisibility(vis);
            rowFc.setVisibility(vis);
        }
    }

    // ---------- 月历 ----------

    private void buildCalendarPool() {
        int fg = getResources().getColor(R.color.fg);
        int dotS = (int) (getResources().getDisplayMetrics().density * 7); // 圆点直径 7dp，太小会看不见
        int dotM = (int) (getResources().getDisplayMetrics().density * 3);
        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < 7; c++) {
                FrameLayout cell = new FrameLayout(this);
                cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                TextView tv = new TextView(this);
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(17);
                tv.setTextColor(fg);
                cell.addView(tv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                View dot = new View(this);
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                dg.setColor(getResources().getColor(R.color.amber));
                dot.setBackgroundDrawable(dg);
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                        dotS, dotS, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                dlp.bottomMargin = dotM;
                dot.setLayoutParams(dlp);
                dot.setVisibility(View.GONE);
                cell.addView(dot);
                row.addView(cell);
                int idx = r * 7 + c;
                cellText[idx] = tv;
                cellDot[idx] = dot;
            }
            calGrid.addView(row);
        }
    }

    private void buildCalendar(Calendar now) {
        int fg = getResources().getColor(R.color.fg);
        int red = getResources().getColor(R.color.red);
        int dim = getResources().getColor(R.color.out_month);
        int accent = getResources().getColor(R.color.accent);
        int todayText = getResources().getColor(R.color.today_text);
        int amber = getResources().getColor(R.color.amber);
        int ban = getResources().getColor(R.color.ban);

        tvCalTitle.setText((now.get(Calendar.MONTH) + 1) + "月");
        tvCalYear.setText(String.valueOf(now.get(Calendar.YEAR)));

        HashSet<Integer> dots = new HashSet<Integer>();
        if (!mTodos.isEmpty()) {
            Calendar m0 = (Calendar) now.clone();
            m0.set(Calendar.DAY_OF_MONTH, 1);
            zero(m0);
            Calendar m1 = (Calendar) now.clone();
            m1.set(Calendar.DAY_OF_MONTH, m1.getActualMaximum(Calendar.DAY_OF_MONTH));
            m1.set(Calendar.HOUR_OF_DAY, 23);
            m1.set(Calendar.MINUTE, 59);
            m1.set(Calendar.SECOND, 59);
            for (int i = 0; i < mTodos.size() && dots.size() < 31; i++) {
                Todo t = mTodos.get(i);
                ArrayList<Long> occs = Recur.expand(t, m0.getTimeInMillis(), m1.getTimeInMillis());
                for (int k = 0; k < occs.size(); k++) {
                    Calendar c = Calendar.getInstance();
                    c.setTimeInMillis(occs.get(k).longValue());
                    dots.add(Integer.valueOf(c.get(Calendar.DAY_OF_MONTH)));
                }
            }
        }

        Calendar first = (Calendar) now.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int lead = dowMon1(first) - 1;
        int len = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        int prevLen;
        {
            Calendar p = (Calendar) now.clone();
            p.add(Calendar.MONTH, -1);
            prevLen = p.getActualMaximum(Calendar.DAY_OF_MONTH);
        }
        int todayD = now.get(Calendar.DAY_OF_MONTH);

        GradientDrawable todayBg = new GradientDrawable();
        float rad = getResources().getDisplayMetrics().density * 5;
        todayBg.setCornerRadius(rad);
        todayBg.setColor(accent);

        for (int i = 0; i < 42; i++) {
            TextView tv = cellText[i];
            int d = i - lead + 1;
            boolean inMonth = d >= 1 && d <= len;
            boolean weekend = i % 7 >= 5;

            // 换算每个格子的真实日期（含前后月的补位格），用于农历与节假日
            int cellY = now.get(Calendar.YEAR);
            int cellM = now.get(Calendar.MONTH) + 1;
            int cellD = d;
            if (!inMonth) {
                Calendar oc = (Calendar) now.clone();
                oc.add(Calendar.MONTH, d < 1 ? -1 : 1);
                cellY = oc.get(Calendar.YEAR);
                cellM = oc.get(Calendar.MONTH) + 1;
                cellD = d < 1 ? prevLen + d : d - len;
            }
            String key = String.format(Locale.US, "%04d-%02d-%02d", cellY, cellM, cellD);

            String dayStr = String.valueOf(cellD);
            boolean offDay = inMonth && mOffDays.containsKey(key);
            boolean banDay = inMonth && mBanDays.containsKey(key);
            boolean hasTodo = inMonth && dots.contains(Integer.valueOf(d));

            // 第二行标签：调休补班“班” > 法定假日名 > 农历（初一月名/农历节日/农历日）
            String label;
            int labelColor = dim;
            if (inMonth && banDay) {
                label = "班";
                labelColor = ban;
            } else {
                label = Lunar.label(cellY, cellM, cellD);
                if (offDay) {
                    String hn = mOffDays.get(key);
                    if (hn != null && hn.length() > 0) label = hn;
                    labelColor = red;
                }
            }

            String text = label == null || label.length() == 0 ? dayStr : dayStr + "\n" + label;
            SpannableString ss = new SpannableString(text);
            if (text.length() > dayStr.length()) {
                int st = dayStr.length() + 1;
                ss.setSpan(new RelativeSizeSpan(0.62f), st, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ForegroundColorSpan(labelColor), st, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(ss);

            if (inMonth && d == todayD) {
                tv.setBackgroundDrawable(todayBg);
                tv.setTextColor(todayText);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
            } else if (!inMonth) {
                tv.setBackgroundDrawable(null);
                tv.setTextColor(dim);
                tv.setTypeface(Typeface.DEFAULT);
            } else if (banDay) {
                tv.setBackgroundDrawable(null);
                tv.setTextColor(fg);
                tv.setTypeface(Typeface.DEFAULT);
            } else if (offDay || weekend) {
                tv.setBackgroundDrawable(null);
                tv.setTextColor(red);
                tv.setTypeface(Typeface.DEFAULT);
            } else if (hasTodo) {
                tv.setBackgroundDrawable(null);
                tv.setTextColor(amber);
                tv.setTypeface(Typeface.DEFAULT);
            } else {
                tv.setBackgroundDrawable(null);
                tv.setTextColor(fg);
                tv.setTypeface(Typeface.DEFAULT);
            }
            cellDot[i].setVisibility(hasTodo ? View.VISIBLE : View.GONE);
        }
    }

    private static void zero(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static int dowMon1(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK);
        return d == 1 ? 7 : d - 1;
    }

    // ---------- 天气 ----------

    private void renderWeather() {
        if (mWx == null) {
            tvTemp.setText("--°");
            tvCond.setText(Store.serverBase(this) == null ? "未配置服务器" : "等待天气数据");
            tvCity.setText("");
            tvMeta.setText("");
            icNow.setType(WeatherIconView.T_NONE);
            for (int i = 0; i < 3; i++) {
                tvFcDay[i].setText("--");
                tvFcTxt[i].setText("--");
                icFc[i].setType(WeatherIconView.T_NONE);
            }
            return;
        }
        tvTemp.setText(Math.round(mWx.temp) + "°");
        tvCond.setText(mWx.condText);
        icNow.setType(WeatherIconView.typeFor(mWx.condText));
        String city = Prefs.get(this, Prefs.K_CITY, "");
        tvCity.setText(city.length() > 0 ? city : "");
        if (mWx.dayMax.length > 0 && mWx.dayMin.length > 0) {
            String meta = "最高 " + Math.round(mWx.dayMax[0]) + "° · 最低 " + Math.round(mWx.dayMin[0]) + "°";
            if (mWx.extra.length() > 0) meta += " · " + mWx.extra;
            tvMeta.setText(meta);
        } else if (mWx.extra.length() > 0) {
            tvMeta.setText(mWx.extra);
        } else {
            tvMeta.setText("");
        }
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < 3; i++) {
            int idx = i + 1; // 明天起的三天
            if (idx < mWx.dayDate.length && mWx.dayDate[idx].length() == 10) {
                try {
                    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    cal.setTime(df.parse(mWx.dayDate[idx]));
                    String label = i == 0 ? "明天" : "周" + "日一二三四五六".charAt(dowSun1(cal));
                    tvFcDay[i].setText(label);
                    tvFcTxt[i].setText(mWx.dayText[idx] + " "
                            + Math.round(mWx.dayMin[idx]) + "°/" + Math.round(mWx.dayMax[idx]) + "°");
                    icFc[i].setType(WeatherIconView.typeFor(mWx.dayText[idx]));
                    continue;
                } catch (Exception ignored) {
                }
            }
            tvFcDay[i].setText("--");
            tvFcTxt[i].setText("--");
            icFc[i].setType(WeatherIconView.T_NONE);
        }
    }

    private static int dowSun1(Calendar c) {
        return c.get(Calendar.DAY_OF_WEEK) - 1;
    }

    // ---------- 待办列表 ----------

    private void buildTodoRows() {
        mRows.clear();
        long now = System.currentTimeMillis();
        ArrayList<Object[]> occs = new ArrayList<Object[]>(); // {Long occ, String title}
        // 上一个待办：最近一次已过去的发生（只往前找 24 小时，太旧的没有参考意义）
        long lookback = now - 24L * 3600000L;
        long prevOcc = 0;
        String prevTitle = null;
        for (int i = 0; i < mTodos.size(); i++) {
            Todo t = mTodos.get(i);
            ArrayList<Long> list = Recur.expand(t, lookback, now + 7L * 86400000L);
            if (list.isEmpty()) continue;
            for (int k = 0; k < list.size(); k++) {
                long occ = list.get(k).longValue();
                if (occ < now) {
                    if (occ > prevOcc) { // 已过去的里取最近的一次
                        prevOcc = occ;
                        prevTitle = t.title;
                    }
                } else {
                    // 未来部分每条待办只取最近一次发生：短周期循环不会占满列表
                    occs.add(new Object[]{list.get(k), t.title});
                    break;
                }
            }
        }
        java.util.Collections.sort(occs, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return ((Long) a[0]).compareTo((Long) b[0]);
            }
        });
        Calendar nowC = Calendar.getInstance();
        Calendar tomorrowC = Calendar.getInstance();
        tomorrowC.add(Calendar.DAY_OF_MONTH, 1);
        // 行结构：{时间, 标题, 是否今天, 是否最近一条(大字), 是否上一个(带框)}
        if (prevTitle != null) {
            Calendar pc = Calendar.getInstance();
            pc.setTimeInMillis(prevOcc);
            // 不写"上一个"字样，时间标签短不折行，靠边框区分
            mRows.add(new String[]{relDay(pc, nowC), prevTitle, "0", "0", "1"});
        }
        for (int i = 0; i < occs.size() && mRows.size() < 8; i++) {
            long occ = ((Long) occs.get(i)[0]).longValue();
            String title = (String) occs.get(i)[1];
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(occ);
            boolean today = sameDay(c, nowC);
            String when;
            if (today) when = "今天 " + HM_FMT.format(c.getTime());
            else if (sameDay(c, tomorrowC)) when = "明天 " + HM_FMT.format(c.getTime());
            else when = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(c.getTime());
            // 最近的一条未来待办始终大字突出（i==0 即未来部分第一条）
            mRows.add(new String[]{when, title, today ? "1" : "0", i == 0 ? "1" : "0", "0"});
        }
        if (mRows.isEmpty()) {
            String hint = Store.serverBase(this) == null ? "离线模式 · 仅时钟日历" : "近期没有安排";
            mRows.add(new String[]{"", hint, "0", "0", "0"});
        }
        mAdapter.notifyDataSetChanged();
        mListBuiltAt = HM_FMT.format(new Date());
        Log.d(TAG, "重排待办 " + mRows.size() + " 行，首行=" + mRows.get(0)[0] + " " + mRows.get(0)[1]);
    }

    /** 相对日期标签：今天/昨天 HH:mm，更旧则 MM-dd HH:mm */
    private String relDay(Calendar c, Calendar nowC) {
        if (sameDay(c, nowC)) return "今天 " + HM_FMT.format(c.getTime());
        Calendar yc = (Calendar) nowC.clone();
        yc.add(Calendar.DAY_OF_MONTH, -1);
        if (sameDay(c, yc)) return "昨天 " + HM_FMT.format(c.getTime());
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(c.getTime());
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
                && a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH);
    }

    private class TodoAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_todo, parent, false);
            }
            TextView when = (TextView) v.findViewById(R.id.tWhen);
            TextView title = (TextView) v.findViewById(R.id.tTitle);
            String[] row = mRows.get(position);
            boolean big = "1".equals(row[3]);
            boolean prev = row.length > 4 && "1".equals(row[4]);
            when.setText(row[0]);
            title.setText(row[1]);
            if (prev) {
                // 上一个待办：琥珀色圆角描边框起来，文字调暗表示已过去
                int dp6 = (int) (getResources().getDisplayMetrics().density * 6);
                GradientDrawable frame = new GradientDrawable();
                frame.setCornerRadius(getResources().getDisplayMetrics().density * 8);
                frame.setStroke((int) (getResources().getDisplayMetrics().density * 1.5f)
                        + 1, getResources().getColor(R.color.amber));
                frame.setColor(0x1AF2B23E); // 极淡的琥珀底色
                v.setBackgroundDrawable(frame);
                v.setPadding(dp6, dp6, dp6, dp6);
                when.setTextSize(13);
                title.setTextSize(15);
                when.setTextColor(getResources().getColor(R.color.dim));
                title.setTextColor(getResources().getColor(R.color.muted));
                title.setTypeface(Typeface.DEFAULT);
                return v;
            }
            v.setBackgroundDrawable(null);
            v.setPadding(0, 0, 0, 0);
            boolean today = "1".equals(row[2]);
            // 最近一条待办放大突出，其余常规字号
            when.setTextSize(big ? 15 : 13);
            title.setTextSize(big ? 20 : 16);
            when.setTextColor(today ? getResources().getColor(R.color.accent) : getResources().getColor(R.color.muted));
            title.setTypeface(today ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return v;
        }
    }
}
