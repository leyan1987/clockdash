package com.clockdash;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 天气数据（当前 + 未来 4 天）。支持两种来源，字段统一：
 *  1) 服务器 /api/weather 的规范化 JSON（高德或服务器端 Open-Meteo）
 *  2) 平板直连 Open-Meteo 的原始 JSON（兜底）
 */
public class WeatherInfo {

    public double temp;
    /** 当前天气中文描述，如“晴”“多云” */
    public String condText = "";
    /** 附加信息，如“湿度 35% · 西北风1-3级”（高德来源才有） */
    public String extra = "";
    public String[] dayDate = new String[0];
    public String[] dayText = new String[0];
    public double[] dayMin = new double[0];
    public double[] dayMax = new double[0];

    /** 解析服务器 /api/weather 返回：{"ok":true,"cond":"晴","temp":8,"humidity":"35","wind":"西北风1-3级","casts":[...]} */
    public static WeatherInfo parseServer(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("ok", false)) return null;
            WeatherInfo w = new WeatherInfo();
            w.temp = root.optDouble("temp", 0);
            w.condText = root.optString("cond", "");
            String extra = root.optString("extra", "");
            if (extra.length() > 0) {
                w.extra = extra;   // 本机缓存的规范化格式
            } else {
                String hum = root.optString("humidity", "");
                String wind = root.optString("wind", "");
                StringBuilder ex = new StringBuilder();
                if (hum.length() > 0) ex.append("湿度 ").append(hum).append("%");
                if (wind.length() > 0) {
                    if (ex.length() > 0) ex.append(" · ");
                    ex.append(wind);
                }
                w.extra = ex.toString();
            }
            fillCasts(w, root.optJSONArray("casts"), null, null, null);
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 Open-Meteo 原始响应 */
    public static WeatherInfo parseOpenMeteo(String json) {
        try {
            JSONObject root = new JSONObject(json);
            WeatherInfo w = new WeatherInfo();
            JSONObject cur = root.optJSONObject("current_weather");
            if (cur != null) {
                w.temp = cur.optDouble("temperature", 0);
                w.condText = codeText(cur.optInt("weathercode", -1));
            }
            JSONObject daily = root.optJSONObject("daily");
            if (daily != null) {
                fillCasts(w, daily.optJSONArray("time"),
                        daily.optJSONArray("weathercode"),
                        daily.optJSONArray("temperature_2m_min"),
                        daily.optJSONArray("temperature_2m_max"));
            }
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析高德 Web 服务 API 原始响应（平板直连用）。
     * extensions=base 才返回 lives，extensions=all 才返回 forecasts，
     * 两者互斥，必须由调用方分别请求后一起传进来。
     */
    public static WeatherInfo parseAmap(String liveJson, String castJson) {
        try {
            if (liveJson == null) return null;
            JSONObject lr = new JSONObject(liveJson);
            if (!"1".equals(lr.optString("status", ""))) return null;
            JSONArray lives = lr.optJSONArray("lives");
            if (lives == null || lives.length() == 0) return null;
            JSONObject live = lives.getJSONObject(0);
            WeatherInfo w = new WeatherInfo();
            w.temp = num(live.optString("temperature", "0"));
            w.condText = live.optString("weather", "");
            String hum = live.optString("humidity", "");
            String wind = amapWind(live.optString("winddirection", ""),
                                   live.optString("windpower", ""));
            StringBuilder ex = new StringBuilder();
            if (hum.length() > 0) ex.append("湿度 ").append(hum).append("%");
            if (wind.length() > 0) {
                if (ex.length() > 0) ex.append(" · ");
                ex.append(wind);
            }
            w.extra = ex.toString();

            if (castJson != null) {
                JSONObject cr = new JSONObject(castJson);
                if ("1".equals(cr.optString("status", ""))) {
                    JSONArray fcs = cr.optJSONArray("forecasts");
                    if (fcs != null && fcs.length() > 0) {
                        JSONArray casts = fcs.getJSONObject(0).optJSONArray("casts");
                        if (casts != null && casts.length() > 0) {
                            int n = casts.length();
                            w.dayDate = new String[n];
                            w.dayText = new String[n];
                            w.dayMin = new double[n];
                            w.dayMax = new double[n];
                            for (int i = 0; i < n; i++) {
                                JSONObject o = casts.getJSONObject(i);
                                w.dayDate[i] = o.optString("date", "");
                                String day = o.optString("dayweather", "");
                                String night = o.optString("nightweather", "");
                                w.dayText[i] = day.length() > 0 ? day : night;
                                w.dayMin[i] = num(o.optString("nighttemp", "0"));
                                w.dayMax[i] = num(o.optString("daytemp", "0"));
                            }
                        }
                    }
                }
            }
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String amapWind(String dir, String power) {
        if (dir.length() == 0 && power.length() == 0) return "";
        String p = power;
        if (p.length() > 0 && !p.contains("级")) p = p + "级";
        return (dir.length() > 0 ? dir : "") + p;
    }

    /**
     * 两种 casts 形态二选一：
     *  服务器版 casts: [{date, cond, min, max}]
     *  Open-Meteo 版: dates / codes / mins / maxs 四个平行数组
     */
    private static void fillCasts(WeatherInfo w, JSONArray dates, JSONArray codes,
                                  JSONArray mins, JSONArray maxs) {
        if (dates == null) return;
        int n = dates.length();
        w.dayDate = new String[n];
        w.dayText = new String[n];
        w.dayMin = new double[n];
        w.dayMax = new double[n];
        for (int i = 0; i < n; i++) {
            if (codes != null) {
                // Open-Meteo 平行数组
                w.dayDate[i] = dates.optString(i, "");
                w.dayText[i] = codeText(codes.optInt(i, -1));
                w.dayMin[i] = mins == null ? 0 : mins.optDouble(i, 0);
                w.dayMax[i] = maxs == null ? 0 : maxs.optDouble(i, 0);
            } else {
                JSONObject o = dates.optJSONObject(i);
                if (o == null) continue;
                w.dayDate[i] = o.optString("date", "");
                w.dayText[i] = o.optString("cond", "");
                w.dayMin[i] = o.optDouble("min", 0);
                w.dayMax[i] = o.optDouble("max", 0);
            }
        }
    }

    /** WMO weathercode → 中文（仅 Open-Meteo 来源使用） */
    public static String codeText(int c) {
        if (c == 0) return "晴";
        if (c == 1 || c == 2) return "多云";
        if (c == 3) return "阴";
        if (c == 45 || c == 48) return "雾";
        if (c >= 51 && c <= 57) return "毛毛雨";
        if (c >= 61 && c <= 67) return "雨";
        if (c >= 71 && c <= 77) return "雪";
        if (c >= 80 && c <= 82) return "阵雨";
        if (c == 85 || c == 86) return "阵雪";
        if (c >= 95) return "雷阵雨";
        return "—";
    }
}
