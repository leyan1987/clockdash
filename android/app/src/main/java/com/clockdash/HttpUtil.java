package com.clockdash;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 极简 HTTP GET（纯 HTTP；老安卓不支持新 TLS，务必走 http://） */
public final class HttpUtil {

    private HttpUtil() {}

    /** HTTP 结果：带上状态码，才能区分「订阅到期 402」与「网络失败」 */
    public static class Result {
        public final int code;
        public final String body;
        Result(int code, String body) { this.code = code; this.body = body; }
        public boolean ok() { return code == 200 && body != null; }
        public boolean expired() { return code == 402; }
    }

    /** 返回带状态码的响应；网络异常 code=-1、body=null */
    public static Result getResult(String url, String token, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "ClockDash/1.0");
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("X-Token", token);
            }
            int code = conn.getResponseCode();
            if (code != 200) return new Result(code, null);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new Result(code, new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return new Result(-1, null);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** 兼容旧调用：返回响应文本，失败返回 null */
    public static String get(String url, String token, int readTimeoutMs) {
        Result r = getResult(url, token, readTimeoutMs);
        return r.body;
    }
}
