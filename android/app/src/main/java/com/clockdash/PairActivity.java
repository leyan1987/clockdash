package com.clockdash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 手机扫码配置：平板在本机起一个临时 HTTP 服务并显示二维码，
 * 手机扫码后打开平板上的配置页，填写服务器地址等基本设置直接写入平板。
 * 仅在本页面存活期间运行，退出即关闭，并用屏幕上的 4 位配对码防误改。
 */
public class PairActivity extends Activity {

    private PairServer mServer;
    private TextView mStatus;
    private String mPin = "";
    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        int pad = px(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getResources().getColor(R.color.bg));

        String ip = lanIp();
        if (ip == null) {
            TextView err = new TextView(this);
            err.setText("未连接 WiFi，无法生成配置二维码。\n请先连接与手机同一局域网。");
            err.setTextColor(getResources().getColor(R.color.fg));
            err.setTextSize(18);
            root.addView(err);
            setContentView(root);
            return;
        }

        mPin = String.valueOf((int) (Math.random() * 9000) + 1000);
        mServer = new PairServer(this);
        final int port = mServer.start(mPin);
        if (port < 0) {
            TextView err = new TextView(this);
            err.setText("本地端口被占用，无法启动配置服务。");
            err.setTextColor(getResources().getColor(R.color.red));
            err.setTextSize(18);
            root.addView(err);
            setContentView(root);
            return;
        }

        // 左侧：二维码 + 配对码
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        Bitmap qr = QrCodec.encode("http://" + ip + ":" + port + "/?pin=" + mPin, 400);
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            left.addView(iv, new LinearLayout.LayoutParams(px(300), px(300)));
        }
        TextView pinView = new TextView(this);
        pinView.setText("配对码  " + mPin);
        pinView.setTextColor(getResources().getColor(R.color.amber));
        pinView.setTextSize(22);
        pinView.setGravity(Gravity.CENTER);
        pinView.setPadding(0, px(12), 0, 0);
        left.addView(pinView);
        root.addView(left);

        // 右侧：说明 + 状态
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rp.leftMargin = px(24);
        right.setLayoutParams(rp);

        TextView title = new TextView(this);
        title.setText("手机扫码配置");
        title.setTextColor(getResources().getColor(R.color.fg));
        title.setTextSize(24);
        right.addView(title);

        TextView help = new TextView(this);
        help.setText("1. 用手机扫左侧二维码（需与平板同一 WiFi）\n"
                + "2. 在手机网页里填写服务器地址、口令等\n"
                + "3. 点“保存到平板”，这里显示成功即生效\n\n"
                + "平板地址: " + ip + ":" + port + "\n"
                + "提交时需要填屏幕上的 4 位配对码");
        help.setTextColor(getResources().getColor(R.color.muted));
        help.setTextSize(15);
        help.setPadding(0, px(12), 0, 0);
        right.addView(help);

        mStatus = new TextView(this);
        mStatus.setText("等待手机扫码…");
        mStatus.setTextColor(getResources().getColor(R.color.accent));
        mStatus.setTextSize(16);
        mStatus.setPadding(0, px(16), 0, 0);
        right.addView(mStatus);

        Button done = new Button(this);
        done.setText("完成");
        done.setTextSize(16);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(48));
        dp.topMargin = px(20);
        done.setLayoutParams(dp);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        right.addView(done);
        root.addView(right);
        setContentView(root);

        mServer.setListener(new PairServer.Listener() {
            @Override
            public void onEvent(final String msg) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mStatus != null) mStatus.setText(msg);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mServer != null) mServer.stopServer();
    }

    private int px(int dp) {
        return (int) (getResources().getDisplayMetrics().density * dp);
    }

    private static String lanIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 极简 HTTP 服务：GET / 配置页，POST /save 保存 */
    static class PairServer {

        interface Listener {
            void onEvent(String msg);
        }

        private final Context mContext;
        private ServerSocket mSocket;
        private Thread mThread;
        private volatile boolean mRunning = false;
        private int mPort = -1;
        private String mPin = "";
        private Listener mListener;

        PairServer(Context ctx) {
            mContext = ctx.getApplicationContext();
        }

        void setListener(Listener l) {
            mListener = l;
        }

        int start(String pin) {
            mPin = pin;
            for (int p = 8766; p <= 8775; p++) {
                try {
                    mSocket = new ServerSocket(p, 4);
                    mPort = p;
                    break;
                } catch (Exception e) {
                    mSocket = null;
                }
            }
            if (mSocket == null) return -1;
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    mRunning = true;
                    while (mRunning) {
                        try {
                            Socket s = mSocket.accept();
                            handle(s);
                        } catch (Exception e) {
                            break;
                        }
                    }
                }
            }, "clockdash-pair");
            mThread.start();
            return mPort;
        }

        void stopServer() {
            mRunning = false;
            if (mSocket != null) {
                try {
                    mSocket.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void notifyEvent(String msg) {
            if (mListener != null) mListener.onEvent(msg);
        }

        private void handle(Socket s) {
            try {
                s.setSoTimeout(5000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
                String line = in.readLine();
                if (line == null) {
                    s.close();
                    return;
                }
                String[] parts = line.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String path = parts.length > 1 ? parts[1] : "/";
                int contentLength = 0;
                while ((line = in.readLine()) != null && line.length() > 0) {
                    String low = line.toLowerCase();
                    if (low.startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        } catch (Exception ignored) {
                        }
                    }
                }
                String body = "";
                if (contentLength > 0 && contentLength < 65536) {
                    char[] buf = new char[contentLength];
                    int n = 0, r;
                    while (n < contentLength && (r = in.read(buf, n, contentLength - n)) > 0) n += r;
                    body = new String(buf, 0, n);
                }

                if ("POST".equals(method) && path.startsWith("/save")) {
                    doSave(s, body);
                } else {
                    String qpin = queryParam(path, "pin");
                    respond(s, pageHtml(qpin));
                }
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void doSave(Socket s, String body) {
            Map<String, String> p = parseForm(body);
            if (!mPin.equals(p.get("pin"))) {
                notifyEvent("手机提交了错误的配对码，已拒绝");
                respond(s, msgHtml("配对码不正确，请核对平板上显示的 4 位数字。"));
                return;
            }
            String url = trim(p.get("url"));
            if (url.length() == 0) {
                respond(s, msgHtml("服务器地址不能为空。"));
                return;
            }
            Prefs.put(mContext, Prefs.K_URL, url);
            Prefs.put(mContext, Prefs.K_TOKEN, trim(p.get("token")));
            if (trim(p.get("city")).length() > 0) Prefs.put(mContext, Prefs.K_CITY, trim(p.get("city")));
            if (trim(p.get("lat")).length() > 0) Prefs.put(mContext, Prefs.K_LAT, trim(p.get("lat")));
            if (trim(p.get("lon")).length() > 0) Prefs.put(mContext, Prefs.K_LON, trim(p.get("lon")));
            AlarmScheduler.schedulePeriodic(mContext);
            notifyEvent("已收到并保存新配置 ✓ 返回设置页可见");
            respond(s, msgHtml("已保存到平板 ✓<br>请回到平板上点「保存并测试连接」确认。"));
        }

        private static String trim(String v) {
            return v == null ? "" : v.trim();
        }

        private static Map<String, String> parseForm(String body) {
            Map<String, String> out = new HashMap<String, String>();
            if (body == null || body.length() == 0) return out;
            String[] pairs = body.split("&");
            for (int i = 0; i < pairs.length; i++) {
                int eq = pairs[i].indexOf('=');
                if (eq < 0) continue;
                try {
                    out.put(URLDecoder.decode(pairs[i].substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {
                }
            }
            return out;
        }

        private static String queryParam(String path, String key) {
            int q = path.indexOf('?');
            if (q < 0) return "";
            Map<String, String> m = parseForm(path.substring(q + 1));
            String v = m.get(key);
            return v == null ? "" : v;
        }

        private static void respond(Socket s, String html) {
            try {
                byte[] b = html.getBytes("UTF-8");
                StringBuilder h = new StringBuilder();
                h.append("HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: text/html; charset=utf-8\r\n");
                h.append("Content-Length: ").append(b.length).append("\r\n");
                h.append("Connection: close\r\n\r\n");
                s.getOutputStream().write(h.toString().getBytes("ISO-8859-1"));
                s.getOutputStream().write(b);
                s.getOutputStream().flush();
            } catch (Exception ignored) {
            }
        }

        private static String pageHtml(String pin) {
            return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>配置时钟看板平板</title><style>"
                    + "body{font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
                    + "max-width:480px;margin:0 auto;padding:16px;color:#1c2733}"
                    + "h2{font-size:20px} label{display:block;font-size:13px;color:#66788a;margin:12px 0 4px}"
                    + "input{width:100%;box-sizing:border-box;padding:10px;font-size:16px;border:1px solid #dde4ea;border-radius:8px}"
                    + "button{margin-top:20px;width:100%;padding:12px;font-size:17px;border:0;border-radius:8px;"
                    + "background:#2f7fd1;color:#fff} .tip{font-size:12.5px;color:#66788a;margin-top:14px}"
                    + "</style></head><body>"
                    + "<h2>配置时钟看板</h2>"
                    + "<form method=\"POST\" action=\"/save\">"
                    + "<label>配对码（平板屏幕上的 4 位数字）</label>"
                    + "<input name=\"pin\" required maxlength=\"4\" inputmode=\"numeric\" value=\"" + pin + "\">"
                    + "<label>服务器地址</label>"
                    + "<input name=\"url\" required placeholder=\"http://123.45.67.89:8765\">"
                    + "<label>口令（服务器 data/server-token.txt）</label>"
                    + "<input name=\"token\" placeholder=\"例如 6587195B\">"
                    + "<label>城市名（选填）</label><input name=\"city\" placeholder=\"北京\">"
                    + "<label>纬度（选填，Open-Meteo 兜底用）</label><input name=\"lat\" placeholder=\"39.90\" inputmode=\"decimal\">"
                    + "<label>经度（选填）</label><input name=\"lon\" placeholder=\"116.40\" inputmode=\"decimal\">"
                    + "<button type=\"submit\">保存到平板</button>"
                    + "</form>"
                    + "<p class=\"tip\">提交后立即生效。高德天气 Key、夜间模式等进阶设置，"
                    + "配置好服务器后在网页后台推送即可。</p>"
                    + "</body></html>";
        }

        private static String msgHtml(String msg) {
            return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>时钟看板</title><style>body{font-family:system-ui,'PingFang SC','Microsoft YaHei',sans-serif;"
                    + "max-width:480px;margin:0 auto;padding:32px 16px;font-size:17px;color:#1c2733}</style></head>"
                    + "<body>" + msg + "</body></html>";
        }
    }
}
