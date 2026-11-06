package com.clockdash;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 数据存取：待办规则的下载/解析/缓存，天气的下载/解析/缓存 */
public final class Store {

    private static final String TODOS_CACHE = "todos_cache.json";
    private static final String WEATHER_CACHE = "weather_cache.json";
    private static final String HOLIDAYS_CACHE = "holidays_cache.json";

    private Store() {}

    // ---------- 待办 ----------

    /** 解析 todos.json 文本；结构非法返回 null */
    public static List<Todo> parseTodos(String json) {
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("todos");
            if (arr == null) return null;
            List<Todo> out = new ArrayList<Todo>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Todo t = new Todo();
                t.id = o.optString("id", "t" + i);
                t.title = o.optString("title", "").trim();
                t.date = o.optString("date", "");
                t.time = o.optString("time", "");
                t.remind = o.optInt("remind", 0);
                t.repeat = o.optString("repeat", "none");
                t.interval = Math.max(1, o.optInt("interval", 1));
                t.windowFrom = o.optString("window_from", null);
                t.windowTo = o.optString("window_to", null);
                t.end = o.optString("end", null);
                JSONArray wd = o.optJSONArray("weekdays");
                if (wd != null && wd.length() > 0) {
                    int[] ws = new int[wd.length()];
                    for (int k = 0; k < wd.length(); k++) ws[k] = wd.optInt(k, 0);
                    t.weekdays = ws;
                }
                if (t.title.length() > 0 && t.date.length() == 10 && t.time.length() >= 4) {
                    out.add(t);
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<Todo> loadTodosCache(Context c) {
        String s = readPrivate(c, TODOS_CACHE);
        if (s == null) return new ArrayList<Todo>();
        List<Todo> l = parseTodos(s);
        return l == null ? new ArrayList<Todo>() : l;
    }

    /** 从服务器拉取待办并写缓存；成功返回列表，失败返回 null（保留旧缓存） */
    public static List<Todo> fetchTodos(Context c) {
        String base = serverBase(c);
        if (base == null) return null;
        String s = HttpUtil.get(base + "/api/todos", Prefs.get(c, Prefs.K_TOKEN, ""), 12000);
        if (s == null) return null;
        List<Todo> l = parseTodos(s);
        if (l == null) return null;
        // 记录数据版本号，供 /api/check 轻量推送比对
        try {
            Prefs.put(c, Prefs.K_TODO_REV, String.valueOf(new org.json.JSONObject(s).optInt("rev", 0)));
        } catch (Exception ignored) {
        }
        writePrivate(c, TODOS_CACHE, s);
        return l;
    }

    // ---------- 天气 ----------

    public static String weatherUrl(Context c) {
        String lat = Prefs.get(c, Prefs.K_LAT, "");
        String lon = Prefs.get(c, Prefs.K_LON, "");
        if (lat.length() == 0 || lon.length() == 0) return null;
        return "http://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current_weather=true&daily=weathercode,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=4";
    }

    /**
     * 取天气：优先走自己服务器的 /api/weather（高德由服务器代理，Key 不下发平板）；
     * 服务器没有可用数据时退回平板直连 Open-Meteo（纯 HTTP）。
     */
    public static WeatherInfo fetchWeather(Context c) {
        String base = serverBase(c);
        if (base != null) {
            String s = HttpUtil.get(base + "/api/weather", Prefs.get(c, Prefs.K_TOKEN, ""), 12000);
            if (s != null) {
                WeatherInfo w = WeatherInfo.parseServer(s);
                if (w != null) {
                    writePrivate(c, WEATHER_CACHE, s);
                    return w;
                }
            }
        }
        String url = weatherUrl(c);
        if (url == null) return null;
        String s2 = HttpUtil.get(url, null, 12000);
        if (s2 == null) return null;
        WeatherInfo w2 = WeatherInfo.parseOpenMeteo(s2);
        if (w2 == null) return null;
        writePrivate(c, WEATHER_CACHE, s2);
        return w2;
    }

    public static WeatherInfo loadWeatherCache(Context c) {
        String s = readPrivate(c, WEATHER_CACHE);
        if (s == null) return null;
        WeatherInfo w = WeatherInfo.parseServer(s);
        return w != null ? w : WeatherInfo.parseOpenMeteo(s);
    }

    public static long todosCacheTime(Context c) {
        return new File(c.getFilesDir(), TODOS_CACHE).lastModified();
    }

    public static long weatherCacheTime(Context c) {
        return new File(c.getFilesDir(), WEATHER_CACHE).lastModified();
    }

    // ---------- 同步兜底（闹钟与界面心跳共用） ----------

    /**
     * 轻量版本号检查：返回 /api/check 的完整响应（含服务器留言 notice），
     * 不可达或响应异常返回 null。必须在后台线程调用。
     */
    public static JSONObject checkOnce(Context c) {
        String base = serverBase(c);
        if (base == null) return null;
        String s = HttpUtil.get(base + "/api/check", Prefs.get(c, Prefs.K_TOKEN, ""), 8000);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            return o.optBoolean("ok", false) ? o : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 判断 /api/check 响应里版本号是否有变化（resp 为 null 时返回 false） */
    public static boolean hasChanges(Context c, JSONObject resp) {
        if (resp == null) return false;
        return resp.optInt("todo_rev", 0) != Prefs.getInt(c, Prefs.K_TODO_REV, 0)
                || resp.optInt("config_rev", 0) != Prefs.getInt(c, Prefs.K_REV, 0);
    }

    /** 兼容旧调用：true 表示服务器有待办/配置变更 */
    public static boolean pushChanged(Context c) {
        JSONObject o = checkOnce(c);
        return o != null && hasChanges(c, o);
    }

    /** 提取 /api/check 响应里的服务器留言文本（无留言返回 null） */
    public static String noticeText(JSONObject resp) {
        if (resp == null) return null;
        JSONObject n = resp.optJSONObject("notice");
        if (n == null) return null;
        String t = n.optString("text", "").trim();
        return t.length() > 0 ? t : null;
    }

    /** 留言去重用的 id（同一留言只弹一次，服务器最多重发 3 次） */
    public static String noticeId(JSONObject resp) {
        if (resp == null) return null;
        JSONObject n = resp.optJSONObject("notice");
        return n == null ? null : n.optString("id", null);
    }

    /**
     * 过期数据自愈：待办/天气缓存超过 maxAgeMs 就重拉一次（maxAgeMs &lt;= 0 表示强制全量）。
     * 返回 true 表示至少有一个缓存文件被真正写新，调用方据此刷新界面。
     * 完全不依赖 AlarmManager / AsyncTask，可在任意后台线程或界面心跳线程调用。
     * 注意：内部每个请求都有连接 + 读取超时，单次最坏几十秒，不会永久挂住。
     */
    public static boolean healStaleData(Context c, long maxAgeMs) {
        if (serverBase(c) == null) return false;
        long now = System.currentTimeMillis();
        boolean todoStale = maxAgeMs <= 0 || now - todosCacheTime(c) > maxAgeMs;
        boolean wxStale = maxAgeMs <= 0 || now - weatherCacheTime(c) > maxAgeMs;
        if (!todoStale && !wxStale) return false;
        if (maxAgeMs > 0) {
            // 过期自愈属于兜底路径：闹钟和界面心跳可能同时调用，且断网时每个请求
            // 都要等 8~12 秒超时。共享 10 分钟退避，避免老设备被反复唤醒拖垮。
            // maxAgeMs<=0 是版本变化后的强制全量同步，不受退避限制。
            long last = Prefs.getLong(c, Prefs.K_LAST_HEAL, 0);
            if (now - last < 10L * 60000L) return false;
            Prefs.putLong(c, Prefs.K_LAST_HEAL, now);
        }
        boolean changed = false;
        if (todoStale) {
            long before = todosCacheTime(c);
            fetchTodos(c);        // 成功会写缓存并更新 todo_rev
            fetchConfigPush(c);   // 成功会更新 config_rev
            fetchHolidays(c);
            if (todosCacheTime(c) != before) changed = true;
        }
        if (wxStale) {
            long before = weatherCacheTime(c);
            fetchWeather(c);
            if (weatherCacheTime(c) != before) changed = true;
        }
        return changed;
    }

    // ---------- 法定节假日 ----------

    /** 从服务器同步放假/调休数据（服务器每天缓存 holiday-cn），成功写入本地缓存 */
    public static void fetchHolidays(Context c) {
        String base = serverBase(c);
        if (base == null) return;
        String s = HttpUtil.get(base + "/api/holidays", Prefs.get(c, Prefs.K_TOKEN, ""), 12000);
        if (s == null) return;
        // 用 JSON 解析判断 ok，不能做 "ok":true 字符串匹配：
        // Python json.dumps 输出带空格（"ok": true），字符串匹配永远失配
        try {
            if (!new org.json.JSONObject(s).optBoolean("ok", false)) return;
            writePrivate(c, HOLIDAYS_CACHE, s);
        } catch (Exception ignored) {
        }
    }

    /** 解析缓存：off = 放假日(date->节日名)，ban = 调休补班日(date->节日名) */
    public static void loadHolidays(Context c, Map<String, String> off, Map<String, String> ban) {
        off.clear();
        ban.clear();
        String s = readPrivate(c, HOLIDAYS_CACHE);
        if (s == null) return;
        try {
            JSONObject root = new JSONObject(s);
            if (!root.optBoolean("ok", false)) return;
            JSONArray arr = root.optJSONArray("days");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String date = o.optString("date", "");
                String name = o.optString("name", "");
                if (date.length() != 10) continue;
                if (o.optBoolean("isOffDay", false)) {
                    off.put(date, name);
                } else {
                    ban.put(date, name.replace("调休", ""));
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- 通用 ----------

    /** 拉取服务器推送的显示配置；rev 比本地新时合并进 Prefs 并返回 true */
    public static boolean fetchConfigPush(Context c) {
        String base = serverBase(c);
        if (base == null) return false;
        String s = HttpUtil.get(base + "/api/config", Prefs.get(c, Prefs.K_TOKEN, ""), 8000);
        if (s == null) return false;
        try {
            JSONObject o = new JSONObject(s);
            int rev = o.optInt("rev", 0);
            if (rev <= Prefs.getInt(c, Prefs.K_REV, 0)) return false;
            if (o.has("city")) Prefs.put(c, Prefs.K_CITY, o.optString("city", ""));
            if (o.optDouble("lat", 0) != 0 || o.optDouble("lon", 0) != 0) {
                Prefs.put(c, Prefs.K_LAT, String.valueOf(o.optDouble("lat", 0)));
                Prefs.put(c, Prefs.K_LON, String.valueOf(o.optDouble("lon", 0)));
            }
            if (o.has("night_start")) Prefs.put(c, Prefs.K_NIGHT_START, o.optString("night_start", "22:30"));
            if (o.has("night_end")) Prefs.put(c, Prefs.K_NIGHT_END, o.optString("night_end", "06:30"));
            if (o.has("night_clock_only")) Prefs.putBool(c, Prefs.K_NIGHT_ONLY, o.optBoolean("night_clock_only", false));
            if (o.has("remind_sound")) Prefs.putBool(c, Prefs.K_SOUND, o.optBoolean("remind_sound", true));
            if (o.has("todos_min")) Prefs.put(c, Prefs.K_TODO_MIN, String.valueOf(o.optInt("todos_min", 10)));
            if (o.has("weather_min")) Prefs.put(c, Prefs.K_WX_MIN, String.valueOf(o.optInt("weather_min", 30)));
            Prefs.put(c, Prefs.K_REV, String.valueOf(rev));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String serverBase(Context c) {
        String url = Prefs.get(c, Prefs.K_URL, "").trim();
        if (url.length() == 0) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static String readPrivate(Context c, String name) {
        try {
            FileInputStream in = c.openFileInput(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 原子写：先写临时文件再改名，老平板掉电/进程被杀不会留下写了一半的缓存
     *  （缓存损坏会让 parseTodos 返回空，待办全部消失、提醒被清空） */
    public static void writePrivate(Context c, String name, String content) {
        try {
            FileOutputStream out = c.openFileOutput(name + ".tmp", Context.MODE_PRIVATE);
            out.write(content.getBytes("UTF-8"));
            out.close();
            File tmp = new File(c.getFilesDir(), name + ".tmp");
            File dst = new File(c.getFilesDir(), name);
            if (!tmp.renameTo(dst)) {
                // 极少数文件系统 rename 失败：退回直接写
                FileOutputStream out2 = c.openFileOutput(name, Context.MODE_PRIVATE);
                out2.write(content.getBytes("UTF-8"));
                out2.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取存储卡根目录下的配置文件（导入配置备用方案） */
    public static String readSdcardConfig() {
        try {
            File f = new File(android.os.Environment.getExternalStorageDirectory(),
                    "clockdash-config.json");
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取内部私有文件（InputStream 版本，供扫码等场景复用） */
    public static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }
}
