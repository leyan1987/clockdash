package com.clockdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/** 平板端设置：服务器地址/口令、扫码导入、位置、夜间模式、刷新间隔 */
public class SettingsActivity extends Activity {

    private static final int REQ_SCAN = 1;

    private EditText etUrl, etToken, etCity, etLat, etLon, etNightStart, etNightEnd, etTodoMin, etWxMin;
    private CheckBox cbNightOnly, cbSound, cbStayOn, cbAutoBoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etUrl = (EditText) findViewById(R.id.etUrl);
        etToken = (EditText) findViewById(R.id.etToken);
        etCity = (EditText) findViewById(R.id.etCity);
        etLat = (EditText) findViewById(R.id.etLat);
        etLon = (EditText) findViewById(R.id.etLon);
        etNightStart = (EditText) findViewById(R.id.etNightStart);
        etNightEnd = (EditText) findViewById(R.id.etNightEnd);
        etTodoMin = (EditText) findViewById(R.id.etTodoMin);
        etWxMin = (EditText) findViewById(R.id.etWxMin);
        cbNightOnly = (CheckBox) findViewById(R.id.cbNightOnly);
        cbSound = (CheckBox) findViewById(R.id.cbSound);
        cbStayOn = (CheckBox) findViewById(R.id.cbStayOn);
        cbAutoBoot = (CheckBox) findViewById(R.id.cbAutoBoot);

        etUrl.setText(Prefs.get(this, Prefs.K_URL, ""));
        etToken.setText(Prefs.get(this, Prefs.K_TOKEN, ""));
        etCity.setText(Prefs.get(this, Prefs.K_CITY, ""));
        etLat.setText(Prefs.get(this, Prefs.K_LAT, ""));
        etLon.setText(Prefs.get(this, Prefs.K_LON, ""));
        etNightStart.setText(Prefs.get(this, Prefs.K_NIGHT_START, "22:30"));
        etNightEnd.setText(Prefs.get(this, Prefs.K_NIGHT_END, "06:30"));
        etTodoMin.setText(String.valueOf(Prefs.getInt(this, Prefs.K_TODO_MIN, 60)));
        etWxMin.setText(String.valueOf(Prefs.getInt(this, Prefs.K_WX_MIN, 60)));
        cbNightOnly.setChecked(Prefs.getBool(this, Prefs.K_NIGHT_ONLY, false));
        cbSound.setChecked(Prefs.getBool(this, Prefs.K_SOUND, true));
        cbStayOn.setChecked(Prefs.getBool(this, Prefs.K_STAY_ON, true));
        cbAutoBoot.setChecked(Prefs.getBool(this, Prefs.K_AUTO_BOOT, true));

        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                test();
            }
        });
        findViewById(R.id.btnScan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(SettingsActivity.this, ScanActivity.class), REQ_SCAN);
            }
        });
        findViewById(R.id.btnPair).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, PairActivity.class));
            }
        });
        findViewById(R.id.btnShowPageQr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPageQr();
            }
        });
        findViewById(R.id.btnImport).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s = Store.readSdcardConfig();
                if (s == null) {
                    toast("未找到存储卡根目录的 clockdash-config.json");
                } else {
                    applyConfigJson(s);
                }
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                finish();
            }
        });

        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            ((TextView) findViewById(R.id.tvVersion)).setText(
                    "ClockDash v" + pi.versionName + " (" + pi.versionCode + ") · "
                            + getString(R.string.app_flavor));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从手机扫码配置页返回后，重新读入最新配置
        etUrl.setText(Prefs.get(this, Prefs.K_URL, ""));
        etToken.setText(Prefs.get(this, Prefs.K_TOKEN, ""));
        etCity.setText(Prefs.get(this, Prefs.K_CITY, ""));
        etLat.setText(Prefs.get(this, Prefs.K_LAT, ""));
        etLon.setText(Prefs.get(this, Prefs.K_LON, ""));
    }

    private void save() {
        Prefs.put(this, Prefs.K_URL, etUrl.getText().toString().trim());
        Prefs.put(this, Prefs.K_TOKEN, etToken.getText().toString().trim());
        Prefs.put(this, Prefs.K_CITY, etCity.getText().toString().trim());
        Prefs.put(this, Prefs.K_LAT, etLat.getText().toString().trim());
        Prefs.put(this, Prefs.K_LON, etLon.getText().toString().trim());
        Prefs.put(this, Prefs.K_NIGHT_START, etNightStart.getText().toString().trim());
        Prefs.put(this, Prefs.K_NIGHT_END, etNightEnd.getText().toString().trim());
        Prefs.put(this, Prefs.K_TODO_MIN, etTodoMin.getText().toString().trim());
        Prefs.put(this, Prefs.K_WX_MIN, etWxMin.getText().toString().trim());
        Prefs.putBool(this, Prefs.K_NIGHT_ONLY, cbNightOnly.isChecked());
        Prefs.putBool(this, Prefs.K_SOUND, cbSound.isChecked());
        Prefs.putBool(this, Prefs.K_STAY_ON, cbStayOn.isChecked());
        Prefs.putBool(this, Prefs.K_AUTO_BOOT, cbAutoBoot.isChecked());
        // 现代 Android 需要用户在系统设置里授予“修改系统设置”权限：直接弹引导
        if (cbStayOn.isChecked() && !Prefs.applyStayOn(this, true)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要“修改系统设置”权限")
                    .setMessage("“充电时屏幕永不休眠”依赖该权限。请点击“去设置”，"
                            + "找到“允许修改系统设置”并打开，然后返回。")
                    .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    android.net.Uri.fromParts("package", getPackageName(), null)));
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        }
        AlarmScheduler.schedulePeriodic(this);
    }

    private void test() {
        final String base = Store.serverBase(this);
        if (base == null) {
            toast("请先填写服务器地址");
            return;
        }
        toast("正在连接 " + base + " …");
        final SettingsActivity activity = this;
        final Context app = getApplicationContext();
        // 独立线程：安卓 4.x 的 AsyncTask 是全进程串行队列，一次挂住会让测试连接永远转圈
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    String s = HttpUtil.get(base + "/api/todos",
                            Prefs.get(app, Prefs.K_TOKEN, ""), 12000);
                    if (s == null) {
                        result = null;
                    } else {
                        List<Todo> l = Store.parseTodos(s);
                        result = l == null ? "格式错误" : String.valueOf(l.size());
                    }
                } catch (Throwable ignored) {
                    result = null;
                }
                final String r = result;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing()) return;
                        if (r == null) {
                            toast("连接失败：检查地址/口令/网络（只能用 http://）");
                        } else if ("格式错误".equals(r)) {
                            toast("已连上，但待办数据格式不对");
                        } else {
                            toast("连接成功，共 " + r + " 条待办 ✓");
                        }
                    }
                });
            }
        }, "clockdash-test").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            applyConfigJson(data.getStringExtra("text"));
        }
    }

    /** 解析配置 JSON：{"v":1,"url":"...","token":"..."} */
    private void applyConfigJson(String text) {
        if (text == null || text.length() == 0) {
            toast("二维码内容为空");
            return;
        }
        try {
            JSONObject o = new JSONObject(text);
            if (!o.has("url")) {
                toast("这不是配置二维码");
                return;
            }
            etUrl.setText(o.optString("url", ""));
            etToken.setText(o.optString("token", ""));
            save();
            toast("已导入配置 ✓ 正在测试连接…");
            test();
        } catch (Exception e) {
            toast("二维码内容无法识别");
        }
    }

    /** 显示“设置页地址”二维码，手机扫码直接打开网页后台 */
    private void showPageQr() {
        String base = Store.serverBase(this);
        if (base == null) {
            toast("请先填写并保存服务器地址");
            return;
        }
        String url = base + "/?token=" + Prefs.get(this, Prefs.K_TOKEN, "");
        Bitmap qr = QrCodec.encode(url, 480);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        box.setPadding(pad, pad, pad, pad);
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            box.addView(iv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView tv = new TextView(this);
        tv.setText("手机扫码打开网页后台：\n" + url);
        tv.setTextSize(14);
        tv.setPadding(0, pad / 2, 0, 0);
        box.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("设置页二维码")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
