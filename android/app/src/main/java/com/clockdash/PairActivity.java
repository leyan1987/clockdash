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
                + "2. 上半页填设置：服务器地址/口令，单机版留空并填高德 Key+行政区码\n"
                + "3. 下半页增删改待办，点「保存待办到平板」后闹钟自动重排\n\n"
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
        private int mPinFails = 0;
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

        /**
         * 配对码统一校验：错误时线性加延迟（0.8s×次数，封顶 5s）。
         * accept 循环单线程串行处理请求，延迟会卡住后续尝试，
         * 局域网设备无法在页面存活期间爆破 4 位配对码。
         */
        private boolean pinOk(String pin) {
            if (mPin != null && mPin.equals(pin)) {
                mPinFails = 0;
                return true;
            }
            mPinFails++;
            try {
                Thread.sleep(Math.min(800L * mPinFails, 5000L));
            } catch (InterruptedException ignored) {
            }
            return false;
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

                String qpin = queryParam(path, "pin");
                if ("POST".equals(method) && path.startsWith("/save")) {
                    doSave(s, body);
                } else if ("POST".equals(method) && path.startsWith("/todos")) {
                    doTodosSave(s, body, qpin);
                } else if ("GET".equals(method) && path.startsWith("/todos")) {
                    doTodosGet(s, qpin);
                } else {
                    respond(s, pageHtml(mContext, qpin));
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
            if (!pinOk(p.get("pin"))) {
                notifyEvent("手机提交了错误的配对码，已拒绝");
                respond(s, msgHtml("配对码不正确，请核对平板上显示的 4 位数字。"));
                return;
            }
            // 关键防线：空值绝不覆盖已有配置。v1.38 的页面不预填，用户点一次
            // 「保存基本设置」就把服务器地址/高德 Key 冲成空串，平板随即失去
            // 数据源、一直读旧缓存（表现就是天气从 7 天退回 3 天）。
            // 手机页面现在会预填，这里再做一层保护；要清空请到平板设置页操作。
            String url = trim(p.get("url"));
            if (url.length() == 0) url = Prefs.get(mContext, Prefs.K_URL, "");
            String key = trim(p.get("amap_key"));
            if (key.length() == 0) key = Prefs.get(mContext, Prefs.K_AMAP_KEY, "");
            String adcode = trim(p.get("adcode"));
            if (adcode.length() == 0) adcode = Prefs.get(mContext, Prefs.K_AMAP_ADCODE, "");
            Prefs.put(mContext, Prefs.K_URL, url);
            Prefs.put(mContext, Prefs.K_TOKEN, trim(p.get("token")));
            if (trim(p.get("city")).length() > 0) Prefs.put(mContext, Prefs.K_CITY, trim(p.get("city")));
            if (trim(p.get("lat")).length() > 0) Prefs.put(mContext, Prefs.K_LAT, trim(p.get("lat")));
            if (trim(p.get("lon")).length() > 0) Prefs.put(mContext, Prefs.K_LON, trim(p.get("lon")));
            Prefs.put(mContext, Prefs.K_AMAP_KEY, key);
            Prefs.put(mContext, Prefs.K_AMAP_ADCODE, adcode);
            AlarmScheduler.schedulePeriodic(mContext);
            notifyEvent(url.length() == 0 ? "已保存配置（未设服务器，走本机天气源）✓" : "已收到并保存新配置 ✓");
            String source = url.length() > 0 ? "服务器版"
                    : (key.length() > 0 && adcode.length() > 0 ? "本机高德" : "未配置天气源");
            respond(s, msgHtml("已保存到平板 ✓<br>当前天气源：" + source
                    + (url.length() == 0 && key.length() == 0
                       ? "<br>建议填服务器地址，或本机高德 Key + 行政区码，否则只能显示旧缓存" : "")
                    + "<br>请回到平板上点「保存并测试连接」确认。"));
        }

        /** 手机读取当前待办（单机版编辑页的数据源） */
        private void doTodosGet(Socket s, String pin) {
            if (!pinOk(pin)) {
                notifyEvent("手机读取待办时配对码错误，已拒绝");
                respondJson(s, "{\"ok\":false,\"error\":\"配对码不正确\"}");
                return;
            }
            respondJson(s, Store.loadTodosJson(mContext));
        }

        /** 手机保存整份待办到本地缓存，并重排提醒闹钟 */
        private void doTodosSave(Socket s, String body, String pin) {
            if (!pinOk(pin)) {
                notifyEvent("手机提交待办时配对码错误，已拒绝");
                respondJson(s, "{\"ok\":false,\"error\":\"配对码不正确\"}");
                return;
            }
            Map<String, String> p = parseForm(body);
            String data = trim(p.get("data"));
            if (data.length() == 0) {
                respondJson(s, "{\"ok\":false,\"error\":\"数据为空\"}");
                return;
            }
            if (Store.saveLocalTodos(mContext, data)) {
                AlarmScheduler.scheduleRemindersAsync(mContext);
                int n = Store.loadTodosCache(mContext).size();
                notifyEvent("已保存 " + n + " 条待办，闹钟已重排 ✓");
                respondJson(s, "{\"ok\":true,\"count\":" + n + "}");
            } else {
                respondJson(s, "{\"ok\":false,\"error\":\"格式不对：标题/日期/时间必填，重复类型需合法\"}");
            }
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
            respond(s, html, "text/html; charset=utf-8");
        }

        private static void respondJson(Socket s, String json) {
            respond(s, json, "application/json; charset=utf-8");
        }

        private static void respond(Socket s, String text, String contentType) {
            try {
                byte[] b = text.getBytes("UTF-8");
                StringBuilder h = new StringBuilder();
                h.append("HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: ").append(contentType).append("\r\n");
                h.append("Content-Length: ").append(b.length).append("\r\n");
                h.append("Connection: close\r\n\r\n");
                s.getOutputStream().write(h.toString().getBytes("ISO-8859-1"));
                s.getOutputStream().write(b);
                s.getOutputStream().flush();
            } catch (Exception ignored) {
            }
        }

        /** 待办编辑页脚本：无框架，手机自带浏览器即可用 */
        private static String todoJs(String pin) {
            return ""
                    + "var PIN='" + pin + "',todos=[];"
                    + "var REPS=[['none','不重复'],['hourly','每小时'],['daily','每天'],"
                    + "['weekly','每周'],['monthly','每月'],['yearly','每年']];"
                    + "function esc(s){s=String(s==null?'':s);return s.split('&').join('&amp;')"
                    + ".split('<').join('&lt;').split('>').join('&gt;')"
                    + ".split(String.fromCharCode(34)).join('&quot;');}"
                    + "function opts(c){var s='';for(var i=0;i<REPS.length;i++){"
                    + "s+='<option value=\"'+REPS[i][0]+'\"'+(REPS[i][0]==c?' selected':'')"
                    + "+'>'+REPS[i][1]+'</option>';}return s;}"
                    + "function render(){var h='';for(var i=0;i<todos.length;i++){var t=todos[i];"
                    + "h+='<div class=row><div class=hd><b>第'+(i+1)+'条</b>"
                    + "<button type=button onclick=del('+i+')>删除</button></div>"
                    + "<label>标题</label><input id=t'+i+' value=\"'+esc(t.title)+'\">"
                    + "<div class=two><div><label>日期</label><input type=date id=d'+i+' value=\"'+esc(t.date)+'\"></div>"
                    + "<div><label>时间</label><input type=time id=m'+i+' value=\"'+esc(t.time)+'\"></div></div>"
                    + "<div class=two><div><label>重复</label><select id=r'+i+'>'+opts(t.repeat)+'</select></div>"
                    + "<div><label>提前提醒(分钟)</label><input type=number id=v'+i+' value='+(t.remind||0)+'></div></div>"
                    + "<div class=two><div><label>间隔 N</label><input type=number min=1 id=i'+i+' value='+(t.interval||1)+'></div>"
                    + "<div><label>星期(1=周一,逗号分隔)</label><input id=w'+i+' value=\"'"
                    + "+esc((t.weekdays||[]).join(','))+'\"></div></div>"
                    + "<label>结束日期(选填)</label><input type=date id=e'+i+' value=\"'+esc(t.end||'')+'\">"
                    + "</div>';}document.getElementById('rows').innerHTML=h;}"
                    + "function val(id){var e=document.getElementById(id);return e?e.value.trim():'';}"
                    + "function collect(){var out=[];for(var i=0;i<todos.length;i++){"
                    + "var t={id:todos[i].id||('t'+Date.now()+'_'+i),title:val('t'+i),"
                    + "date:val('d'+i),time:val('m'+i),repeat:val('r'+i),"
                    + "remind:parseInt(val('v'+i),10)||0,interval:parseInt(val('i'+i),10)||1};"
                    + "var w=val('w'+i);if(w){var a=[];var ps=w.split(',');for(var j=0;j<ps.length;j++){"
                    + "var n=parseInt(ps[j],10);if(n>=1&&n<=7)a.push(n);}if(a.length)t.weekdays=a;}"
                    + "var e=val('e'+i);if(e)t.end=e;out.push(t);}return out;}"
                    + "function del(i){todos.splice(i,1);render();}"
                    + "function add(){todos.push({title:'',date:'',time:'08:00',repeat:'none',remind:0,interval:1});render();}"
                    + "function tip(s){document.getElementById('msg').textContent=s;}"
                    + "function load(){var x=new XMLHttpRequest();x.onreadystatechange=function(){"
                    + "if(x.readyState==4&&x.status==200){try{var j=JSON.parse(x.responseText);"
                    + "todos=j.todos||[];render();tip('已读取 '+todos.length+' 条待办');}"
                    + "catch(e){tip('读取失败');}}};x.open('GET','/todos?pin='+encodeURIComponent(PIN),true);x.send();}"
                    + "function save(){var data=JSON.stringify({todos:collect()});"
                    + "var body='pin='+encodeURIComponent(PIN)+'&data='+encodeURIComponent(data);"
                    + "var x=new XMLHttpRequest();x.onreadystatechange=function(){"
                    + "if(x.readyState==4&&x.status==200){try{var j=JSON.parse(x.responseText);"
                    + "tip(j.ok?('已保存 '+j.count+' 条待办 ✓'):('失败：'+j.error));}"
                    + "catch(e){tip('保存失败');}}};x.open('POST','/todos',true);"
                    + "x.setRequestHeader('Content-Type','application/x-www-form-urlencoded; charset=UTF-8');x.send(body);}"
                    + "document.getElementById('addBtn').onclick=add;"
                    + "document.getElementById('saveBtn').onclick=save;"
                    + "load();";
        }

        /** HTML 属性值转义，避免已存配置里的特殊字符破坏表单 */
        private static String attr(String v) {
            if (v == null) return "";
            return v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
        }

        /**
         * 配置页必须把已保存的配置全部预填回去：否则用户打开页面点一次
         * 「保存基本设置」，空输入框会把本机已有的高德 Key/adcode 清空，
         * 天气随即从一周退回三天（实测踩到）。
         */
        private static String pageHtml(Context c, String pin) {
            String url = Prefs.get(c, Prefs.K_URL, "");
            String token = Prefs.get(c, Prefs.K_TOKEN, "");
            String city = Prefs.get(c, Prefs.K_CITY, "");
            String lat = Prefs.get(c, Prefs.K_LAT, "");
            String lon = Prefs.get(c, Prefs.K_LON, "");
            String key = Prefs.get(c, Prefs.K_AMAP_KEY, "");
            String adcode = Prefs.get(c, Prefs.K_AMAP_ADCODE, "");
            return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>配置时钟看板平板</title><style>"
                    + "body{font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
                    + "max-width:480px;margin:0 auto;padding:16px;color:#1c2733}"
                    + "h2{font-size:20px} label{display:block;font-size:13px;color:#66788a;margin:12px 0 4px}"
                    + "input{width:100%;box-sizing:border-box;padding:10px;font-size:16px;border:1px solid #dde4ea;border-radius:8px}"
                    + "button{margin-top:20px;width:100%;padding:12px;font-size:17px;border:0;border-radius:8px;"
                    + "background:#2f7fd1;color:#fff} .tip{font-size:12.5px;color:#66788a;margin-top:14px}"
                    + ".row{border:1px solid #dde4ea;border-radius:10px;padding:10px;margin-top:12px}"
                    + ".hd{display:flex;justify-content:space-between;align-items:center}"
                    + ".hd button{margin:0;width:auto;padding:4px 12px;font-size:13px;background:#e6ebf1;color:#d05a4e}"
                    + ".two{display:flex;gap:8px}.two>div{flex:1;min-width:0}"
                    + "select{width:100%;box-sizing:border-box;padding:9px;font-size:16px;"
                    + "border:1px solid #dde4ea;border-radius:8px;background:#fff}"
                    + "hr{margin:20px 0;border:0;border-top:1px solid #dde4ea}"
                    + "#msg{color:#2e9e5b;font-size:14px;margin-top:10px}"
                    + "</style></head><body>"
                    + "<h2>配置时钟看板 · 单机版</h2>"
                    + "<form method=\"POST\" action=\"/save\">"
                    + "<label>配对码（平板屏幕上的 4 位数字）</label>"
                    + "<input name=\"pin\" required maxlength=\"4\" inputmode=\"numeric\" value=\"" + pin + "\">"
                    + "<label>服务器地址（单机版留空）</label>"
                    + "<input name=\"url\" placeholder=\"http://123.45.67.89:8765\" value=\"" + attr(url) + "\">"
                    + "<label>口令（购买服务器版时才填）</label>"
                    + "<input name=\"token\" placeholder=\"例如 6587195B\" value=\"" + attr(token) + "\">"
                    + "<label>城市名（选填）</label><input name=\"city\" placeholder=\"兰山区\" value=\"" + attr(city) + "\">"
                    + "<div class=\"two\"><div><label>纬度</label>"
                    + "<input name=\"lat\" placeholder=\"35.06\" inputmode=\"decimal\" value=\"" + attr(lat) + "\"></div>"
                    + "<div><label>经度</label>"
                    + "<input name=\"lon\" placeholder=\"118.35\" inputmode=\"decimal\" value=\"" + attr(lon) + "\"></div></div>"
                    + "<label>高德 Key（单机版天气用，Web服务类型）</label>"
                    + "<input name=\"amap_key\" placeholder=\"自己注册的 Key\" value=\"" + attr(key) + "\">"
                    + "<label>行政区码（6 位）</label>"
                    + "<input name=\"adcode\" placeholder=\"371302\" inputmode=\"numeric\" value=\"" + attr(adcode) + "\">"
                    + "<button type=\"submit\">保存基本设置到平板</button>"
                    + "</form>"
                    + "<hr><h2>待办编辑</h2>"
                    + "<p class=\"tip\">单机版待办存在平板本机。改完点「保存待办到平板」，提醒闹钟自动重排。</p>"
                    + "<div id=\"rows\"></div>"
                    + "<button type=\"button\" id=\"addBtn\">＋ 添加一条</button>"
                    + "<button type=\"button\" id=\"saveBtn\">保存待办到平板</button>"
                    + "<div id=\"msg\"></div>"
                    + "<p class=\"tip\">星期留空表示跟随开始日期的星期；间隔 N 用于循环规则。</p>"
                    + "<script>" + todoJs(pin) + "</script>"
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
