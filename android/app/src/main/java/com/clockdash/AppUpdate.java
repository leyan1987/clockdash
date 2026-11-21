package com.clockdash;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 应用自更新（OTA）：
 * - 服务端在 /api/check 响应里捎带 {"app":{code,name,file,md5,size,notes}}，
 *   也可经 /api/app/check 主动查询（设置页"检查更新"按钮）。
 * - 发现新版本后后台静默下载到应用专属目录，边下边算 MD5，校验通过才算就绪。
 * - 就绪后通知栏提示"点此安装"，点按发起系统安装器；防篡改由 APK 签名兜底
 *   （签名与已装应用不一致时系统直接拒绝安装）。
 * - 安装通道按系统版本分流：
 *     Android 5.0+：框架 PackageInstaller 会话流式写入（无 FileProvider、零第三方依赖），
 *                   Android 8+ 需用户授予"安装未知应用"；
 *     Android 4.x ：ACTION_VIEW + file:// Uri（targetSdk 15 允许）。
 */
public final class AppUpdate {

    /** 通知栏点按：发起安装 */
    public static final String ACTION_INSTALL_UPDATE = "com.clockdash.INSTALL_UPDATE";
    /** PackageInstaller 会话回调 */
    public static final String ACTION_INSTALL_STATUS = "com.clockdash.INSTALL_STATUS";

    private AppUpdate() {
    }

    /** 本机 versionCode（gradle 里同步维护 resValue integer/app_code） */
    public static int versionCode(Context c) {
        return c.getResources().getInteger(R.integer.app_code);
    }

    /** 本机 flavor 名（resValue 注入：legacy / modern） */
    public static String flavor(Context c) {
        return c.getString(R.string.app_flavor);
    }

    /** 解析更新信息；无更新 / 版本不比本机新 / 字段不合法时返回 null */
    public static JSONObject parseUpdate(JSONObject resp, int currentCode) {
        try {
            JSONObject a = resp == null ? null : resp.optJSONObject("app");
            if (a == null || a.optInt("code", 0) <= currentCode) return null;
            if (!a.optString("file", "").endsWith(".apk")) return null;
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主动查询服务器是否有新版本（设置页按钮）。无服务器/无更新返回 null */
    public static JSONObject checkNow(Context c) {
        String base = Store.serverBase(c);
        if (base == null) return null;
        String s = HttpUtil.get(base + "/api/app/check?flavor=" + flavor(c)
                + "&code=" + versionCode(c), Prefs.get(c, Prefs.K_TOKEN, ""), 12000);
        if (s == null) return null;
        try {
            return parseUpdate(new JSONObject(s), versionCode(c));
        } catch (Throwable t) {
            return null;
        }
    }

    /** /api/check 的捎带入口：发现新版本就下载，就绪后提示安装 */
    public static void onCheck(final Context c, JSONObject resp) {
        try {
            final JSONObject upd = parseUpdate(resp, versionCode(c));
            if (upd == null) return;
            final int code = upd.optInt("code", 0);
            if (Prefs.getInt(c, Prefs.K_APP_DL_CODE, 0) >= code
                    && new File(Prefs.get(c, Prefs.K_APP_FILE, "")).isFile()) {
                // 已下载过这个版本：确保用户收到过一次安装提示后不再打扰
                if (Prefs.getInt(c, Prefs.K_APP_NOTIFIED, 0) != code) notifyReady(c, upd);
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String path = download(c, upd);
                    if (path != null) notifyReady(c, upd);
                }
            }, "clockdash-ota").start();
        } catch (Throwable ignored) {
        }
    }

    /** 后台下载（同步方法，调用方自备线程）。成功返回就绪文件路径，失败 null */
    public static String download(Context c, JSONObject upd) {
        String base = Store.serverBase(c);
        if (base == null) return null;
        String file = upd.optString("file", "");
        String md5 = upd.optString("md5", "").trim();
        int code = upd.optInt("code", 0);
        File dir = updateDir(c);
        File dst = new File(dir, "ClockDash-" + flavor(c) + "-" + code + ".apk");
        // 断点续传不做（300KB 一次下完）；已就绪且校验匹配则免重复下载
        if (dst.isFile() && md5.length() > 0 && md5Matches(dst, md5)) {
            markReady(c, code, dst);
            return dst.getPath();
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/apps/" + file).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            String tok = Prefs.get(c, Prefs.K_TOKEN, "");
            if (tok.length() > 0) conn.setRequestProperty("X-Token", tok);
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            File tmp = new File(dir, dst.getName() + ".part");
            MessageDigest md = MessageDigest.getInstance("MD5");
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                total += n;
                if (total > 50L * 1024 * 1024) break;   // 上限 50MB，防异常响应撑爆存储
            }
            out.close();
            in.close();
            if (md5.length() > 0 && !hex(md.digest()).equalsIgnoreCase(md5)) {
                tmp.delete();   // 下载损坏：丢弃重来，下一轮 check 会再试
                return null;
            }
            if (!tmp.renameTo(dst)) {
                dst.delete();
                if (!tmp.renameTo(dst)) return null;
            }
            markReady(c, code, dst);
            return dst.getPath();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static File updateDir(Context c) {
        File d = c.getExternalFilesDir("update");
        if (d == null) d = c.getDir("update", 0);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void markReady(Context c, int code, File f) {
        Prefs.put(c, Prefs.K_APP_FILE, f.getPath());
        Prefs.put(c, Prefs.K_APP_DL_CODE, String.valueOf(code));
    }

    private static boolean md5Matches(File f, String expect) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            return hex(md.digest()).equalsIgnoreCase(expect);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append("0123456789abcdef".charAt((x >> 4) & 0xf))
                .append("0123456789abcdef".charAt(x & 0xf));
        return sb.toString();
    }

    /** 安装就绪通知：点按触发 InstallReceiver → install() */
    public static void notifyReady(Context c, JSONObject upd) {
        try {
            int code = upd.optInt("code", 0);
            Prefs.put(c, Prefs.K_APP_NOTIFIED, String.valueOf(code));
            String name = upd.optString("name", String.valueOf(code));
            String notes = upd.optString("notes", "");
            AlarmScheduler.ensureChannel(c);
            Intent i = new Intent(c, InstallReceiver.class).setAction(ACTION_INSTALL_UPDATE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 3002, i, AlarmScheduler.piFlags());
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, AlarmScheduler.CHANNEL_ID)
                    : new Notification.Builder(c);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_DEFAULT);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done);
            b.setContentTitle("看板新版本 v" + name + " 已就绪");
            b.setContentText("点此安装升级（约 300KB，安装时请保持充电）");
            if (notes.length() > 0 && Build.VERSION.SDK_INT >= 16) {
                b.setStyle(new android.app.Notification.BigTextStyle().bigText(notes));
            }
            b.setContentIntent(pi);
            b.setAutoCancel(true);
            Notification n = Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
            if (n != null) {
                ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE))
                        .notify(2002, n);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 发起安装。返回错误文案；null 表示已交给系统安装器（legacy 直接弹安装页，
     * modern 经 PackageInstaller 回调弹确认框）。
     */
    public static String install(Context c) {
        String path = Prefs.get(c, Prefs.K_APP_FILE, "");
        final File f = path.length() > 0 ? new File(path) : null;
        if (f == null || !f.isFile()) return "没有已下载的更新包";
        if (Build.VERSION.SDK_INT >= 26) {
            PackageManager pm = c.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.fromParts("package", c.getPackageName(), null));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                } catch (Throwable ignored) {
                }
                return "请先允许“安装未知应用”，返回后再点一次安装";
            }
        }
        if (Build.VERSION.SDK_INT >= 21) {
            OutputStream out = null;
            PackageInstaller.Session s = null;
            try {
                PackageInstaller pm = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sid = piSession(pm, sp);
                s = piOpen(pm, sid);
                InputStream in = new FileInputStream(f);
                out = s.openWrite("ClockDash", 0, f.length());
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
                out.close();
                in.close();
                Intent cb = new Intent(c, InstallReceiver.class).setAction(ACTION_INSTALL_STATUS);
                PendingIntent pi = PendingIntent.getBroadcast(c, 3001, cb, AlarmScheduler.piFlags());
                s.commit(pi.getIntentSender());
                return null;
            } catch (Throwable t) {
                if (s != null) {
                    try {
                        s.abandon();
                    } catch (Throwable ignored) {
                    }
                }
                return "安装发起失败：" + t.getMessage();
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        // Android 4.x：系统安装器直接打开（targetSdk 15 允许 file:// Uri）
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return null;
        } catch (Throwable t) {
            return "请开启“未知来源”后重试";
        }
    }

    // PackageInstaller 在 minSdk 15 的工程里要显式隔离调用（legacy 设备不会走到）
    private static int piSession(PackageInstaller pm, PackageInstaller.SessionParams sp) throws Exception {
        return pm.createSession(sp);
    }

    private static PackageInstaller.Session piOpen(PackageInstaller pm, int sid) throws Exception {
        return pm.openSession(sid);
    }
}
