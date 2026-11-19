package com.clockdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 本机待办管理：喜欢直接在平板上操作的用户可在此增删改。
 * 数据写到 Store 的本地缓存，复用手机扫码配置页同一套存储与校验，
 * 保存后重排提醒闹钟，回到看板即可看到变化。
 */
public class TodoEditActivity extends Activity {

    private static final String[] REPS = {"none", "hourly", "daily", "weekly", "monthly", "yearly"};
    private static final String[] REP_LABELS = {"不重复", "每小时", "每天", "每周", "每月", "每年"};

    private ArrayList<Todo> mTodos = new ArrayList<Todo>();
    private ListView mList;
    private BaseAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todo_edit);
        mTodos = new ArrayList<Todo>(Store.loadTodosCache(this));
        mList = (ListView) findViewById(R.id.listEditTodos);
        mAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mTodos.size();
            }

            @Override
            public Object getItem(int position) {
                return mTodos.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
                }
                Todo t = mTodos.get(position);
                ((TextView) v.findViewById(android.R.id.text1)).setText(
                        (position + 1) + ". " + t.title);
                String rep = "不重复";
                for (int i = 0; i < REPS.length; i++) {
                    if (REPS[i].equals(t.repeat)) {
                        rep = REP_LABELS[i];
                        break;
                    }
                }
                ((TextView) v.findViewById(android.R.id.text2)).setText(
                        t.date + " " + t.time + " · " + rep);
                return v;
            }
        };
        mList.setAdapter(mAdapter);

        mList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View v,
                                    int position, long id) {
                showEditor(position);
            }
        });
        mList.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View v,
                                           final int position, long id) {
                new AlertDialog.Builder(TodoEditActivity.this)
                        .setTitle("删除这条待办？")
                        .setMessage(mTodos.get(position).title)
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                mTodos.remove(position);
                                mAdapter.notifyDataSetChanged();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            }
        });

        findViewById(R.id.btnAddTodo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditor(-1);
            }
        });
        findViewById(R.id.btnSaveTodos).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndFinish();
            }
        });
    }

    /** position=-1 表示新增 */
    private void showEditor(final int position) {
        View v = getLayoutInflater().inflate(R.layout.dialog_todo_edit, null);
        Todo t = position >= 0 ? mTodos.get(position) : null;

        ((EditText) v.findViewById(R.id.etTitle)).setText(t == null ? "" : t.title);
        ((EditText) v.findViewById(R.id.etDate)).setText(t == null ? "" : t.date);
        ((EditText) v.findViewById(R.id.etTime)).setText(t == null ? "08:00" : t.time);
        ((EditText) v.findViewById(R.id.etInterval)).setText(
                String.valueOf(t == null || t.interval <= 0 ? 1 : t.interval));
        ((EditText) v.findViewById(R.id.etRemind)).setText(
                String.valueOf(t == null ? 0 : t.remind));
        ((EditText) v.findViewById(R.id.etWeekdays)).setText(
                t == null || t.weekdays == null ? "" : join(t.weekdays));
        ((EditText) v.findViewById(R.id.etWindowFrom)).setText(
                t == null || t.windowFrom == null ? "" : t.windowFrom);
        ((EditText) v.findViewById(R.id.etWindowTo)).setText(
                t == null || t.windowTo == null ? "" : t.windowTo);
        ((EditText) v.findViewById(R.id.etEnd)).setText(
                t == null || t.end == null ? "" : t.end);

        Spinner sp = (Spinner) v.findViewById(R.id.spRepeat);
        ArrayAdapter<String> sa = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, REP_LABELS);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(sa);
        if (t != null) {
            for (int i = 0; i < REPS.length; i++) {
                if (REPS[i].equals(t.repeat)) {
                    sp.setSelection(i);
                    break;
                }
            }
        }

        final View view = v;
        new AlertDialog.Builder(this)
                .setTitle(position < 0 ? "新增待办" : "编辑待办")
                .setView(v)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Todo edited = readFromView(view, position < 0 ? null : mTodos.get(position));
                        if (edited == null) {
                            Toast.makeText(TodoEditActivity.this,
                                    "标题不能为空，日期需 yyyy-MM-dd，时间需 HH:mm",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (position < 0) mTodos.add(edited);
                        else mTodos.set(position, edited);
                        mAdapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Todo readFromView(View v, Todo base) {
        String title = text(v, R.id.etTitle);
        String date = text(v, R.id.etDate);
        String time = text(v, R.id.etTime);
        // 严格格式校验：日期须真实存在（2026-02-30 拒绝），时间不超 23:59
        if (title.length() == 0 || !Store.validDate(date) || !Store.validTime(time)) return null;
        Todo t = base == null ? new Todo() : base;
        t.id = base == null || base.id == null || base.id.length() == 0
                ? "t" + System.currentTimeMillis() : base.id;
        t.title = title;
        t.date = date;
        t.time = time;
        Spinner sp = (Spinner) v.findViewById(R.id.spRepeat);
        t.repeat = REPS[sp.getSelectedItemPosition()];
        t.interval = intOf(text(v, R.id.etInterval), 1);
        t.remind = intOf(text(v, R.id.etRemind), 0);
        String wd = text(v, R.id.etWeekdays);
        t.weekdays = parseWeekdays(wd);
        String wf = text(v, R.id.etWindowFrom);
        t.windowFrom = wf.length() > 0 ? wf : null;
        String wt = text(v, R.id.etWindowTo);
        t.windowTo = wt.length() > 0 ? wt : null;
        String end = text(v, R.id.etEnd);
        t.end = end.length() == 10 ? end : null;
        return t;
    }

    private void saveAndFinish() {
        try {
            JSONArray arr = new JSONArray();
            for (Todo t : mTodos) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", t.title);
                o.put("date", t.date);
                o.put("time", t.time);
                o.put("repeat", t.repeat);
                o.put("remind", t.remind);
                o.put("interval", t.interval <= 0 ? 1 : t.interval);
                if (t.weekdays != null && t.weekdays.length > 0) {
                    JSONArray wd = new JSONArray();
                    for (int d : t.weekdays) wd.put(d);
                    o.put("weekdays", wd);
                }
                if (t.windowFrom != null && t.windowFrom.length() > 0) o.put("window_from", t.windowFrom);
                if (t.windowTo != null && t.windowTo.length() > 0) o.put("window_to", t.windowTo);
                if (t.end != null && t.end.length() == 10) o.put("end", t.end);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("todos", arr);
            if (!Store.saveLocalTodos(this, root.toString())) {
                Toast.makeText(this, "保存失败：请检查标题/日期/时间", Toast.LENGTH_LONG).show();
                return;
            }
            AlarmScheduler.scheduleRemindersAsync(this);
            Toast.makeText(this, "已保存 " + mTodos.size() + " 条待办，提醒已重排", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "保存异常：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String text(View v, int id) {
        EditText e = (EditText) v.findViewById(id);
        return e == null ? "" : e.getText().toString().trim();
    }

    private static int intOf(String s, int dft) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return dft;
        }
    }

    private static int[] parseWeekdays(String s) {
        if (s == null || s.trim().length() == 0) return null;
        ArrayList<Integer> out = new ArrayList<Integer>();
        String[] parts = s.split(",");
        for (String p : parts) {
            int n = intOf(p.trim(), -1);
            if (n >= 1 && n <= 7) out.add(n);
        }
        if (out.size() == 0) return null;
        int[] a = new int[out.size()];
        for (int i = 0; i < out.size(); i++) a[i] = out.get(i);
        return a;
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
