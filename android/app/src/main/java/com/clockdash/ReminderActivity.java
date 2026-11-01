package com.clockdash;

import android.app.Activity;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 全屏提醒弹窗（点亮屏幕、响铃、震动；老系统上可从后台直接拉起） */
public class ReminderActivity extends Activity {

    private MediaPlayer mPlayer;
    private Vibrator mVibrator;
    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_reminder);
        String title = getIntent().getStringExtra("title");
        long occ = getIntent().getLongExtra("occ", 0);
        boolean sound = getIntent().getBooleanExtra("sound", true);

        TextView tvTitle = (TextView) findViewById(R.id.tvRTitle);
        TextView tvTime = (TextView) findViewById(R.id.tvRTime);
        tvTitle.setText(title == null ? "" : title);
        if (occ > 0) {
            String t = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(occ));
            tvTime.setText("时间：" + t);
        }

        findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnSnooze).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = getIntent().getStringExtra("title");
                boolean s = getIntent().getBooleanExtra("sound", true);
                AlarmScheduler.snooze(ReminderActivity.this, t, 10 * 60000L, s);
                finish();
            }
        });

        if (sound) {
            startSound();
        }
        // 2 分钟后自动关闭
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 120000);
    }

    private void startSound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            mPlayer = new MediaPlayer();
            mPlayer.setDataSource(this, uri);
            mPlayer.setLooping(true);
            mPlayer.prepare();
            mPlayer.start();
        } catch (Exception e) {
            mPlayer = null;
        }
        try {
            mVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            mVibrator.vibrate(new long[]{0, 400, 400, 400, 400, 800}, 0);
        } catch (Exception e) {
            mVibrator = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPlayer != null) {
            try {
                mPlayer.stop();
                mPlayer.release();
            } catch (Exception ignored) {
            }
            mPlayer = null;
        }
        if (mVibrator != null) {
            mVibrator.cancel();
            mVibrator = null;
        }
        mHandler.removeCallbacksAndMessages(null);
    }
}
