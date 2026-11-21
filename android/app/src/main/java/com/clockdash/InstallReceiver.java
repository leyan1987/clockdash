package com.clockdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * OTA 安装回调：
 * - ACTION_INSTALL_UPDATE：通知栏"点此安装"/设置页按钮 → 发起安装
 * - ACTION_INSTALL_STATUS：PackageInstaller 会话结果。非静默安装时系统返回
 *   PENDING_USER_ACTION + 一条确认意图，必须把它启动出来用户才能看到安装页。
 */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (AppUpdate.ACTION_INSTALL_UPDATE.equals(action)) {
            String err = AppUpdate.install(context);
            if (err != null) {
                Toast.makeText(context.getApplicationContext(), err, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (AppUpdate.ACTION_INSTALL_STATUS.equals(action)) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (Throwable ignored) {
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                // SUCCESS 时系统会杀掉本应用进程，通常收不到；失败时给个明确提示
                Toast.makeText(context.getApplicationContext(),
                        "安装未完成（错误码 " + status + "）", Toast.LENGTH_LONG).show();
            }
        }
    }
}
