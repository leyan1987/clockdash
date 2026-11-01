#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""时钟看板后台服务器（纯标准库，Python 3.7+）

接口：
  GET  /             设置页（web/index.html）
  POST /api/login    网页登录（用户名+口令 → 会话）
  POST /api/logout   退出登录
  GET  /api/users    用户列表（仅管理员）
  POST /api/users    用户管理：add/passwd/del（仅管理员）
  GET  /api/ping     连通性测试（需口令/会话）
  GET  /api/todos    读取待办规则（需口令/会话）
  POST /api/todos    保存待办规则（需口令/会话）
  GET  /api/config   读取推送配置（需口令/会话；高德Key仅管理员 full=1）
  POST /api/config   保存推送配置，rev 自动 +1（需口令/会话）
  GET  /api/weather  天气（服务器代理高德/Open-Meteo，带缓存，需口令/会话）
  GET  /api/holidays 法定节假日/调休（代理 holiday-cn，每日缓存，需口令/会话）
  GET  /api/check    数据版本号检查（轻量推送轮询，需口令/会话）

鉴权两通道：
  设备口令 token（平板/链接直达，权限等同管理员）
  网页会话 X-Session（/api/login 签发，30 天有效）
用户库 data/users.json（PBKDF2 加盐哈希）；初始用户 admin 口令 = 设备口令

用法：
  python server.py [端口]        默认端口 8765
口令：
  首次运行自动生成 data/server-token.txt，启动时打印带口令的设置页地址。
"""
import hashlib
import hmac
import json
import os
import random
import re
import secrets
import sys
import threading
import time
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

BASE = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(BASE, "web")
DATA_DIR = os.path.join(BASE, "data")
TOKEN_FILE = os.path.join(DATA_DIR, "server-token.txt")
TODOS_FILE = os.path.join(DATA_DIR, "todos.json")
CONFIG_FILE = os.path.join(DATA_DIR, "app-config.json")
HOLIDAY_CACHE_FILE = os.path.join(DATA_DIR, "holiday-cache.json")
STATE_FILE = os.path.join(DATA_DIR, "state.json")
USERS_FILE = os.path.join(DATA_DIR, "users.json")
SESSIONS_FILE = os.path.join(DATA_DIR, "sessions.json")

SESSION_TTL = 30 * 86400   # 网页登录会话有效期 30 天
_PBKDF2_ITER = 100000      # 口令哈希迭代次数

REPEATS = ("none", "hourly", "daily", "weekly", "monthly", "yearly")

_SAVE_LOCK = threading.Lock()  # 保护 todo_rev 的读改写与文件原子写


def ensure_data():
    os.makedirs(DATA_DIR, exist_ok=True)
    if not os.path.exists(TOKEN_FILE):
        # 16 位 hex（64bit）；老版本是 8 位，如需升级强度删除该文件重启即可
        with open(TOKEN_FILE, "w", encoding="utf-8") as f:
            f.write(secrets.token_hex(8).upper())
    if not os.path.exists(USERS_FILE):
        # 初始用户：admin 的口令绑定为当前设备口令
        u = hash_password(read_token())
        save_users({"users": [{"username": "admin", "role": "admin", **u}]})
    if not os.path.exists(STATE_FILE):
        atomic_write(STATE_FILE, json.dumps({"todo_rev": 0}))
    if not os.path.exists(TODOS_FILE):
        sample = {
            "version": 1, "rev": 0,
            "todos": [
                {"id": "t1", "title": "欢迎使用时钟看板，在设置页编辑我", "date": "2026-11-01",
                 "time": "09:00", "remind": 0, "repeat": "none"},
            ],
        }
        atomic_write(TODOS_FILE, json.dumps(sample, ensure_ascii=False, indent=2))
    if not os.path.exists(CONFIG_FILE):
        cfg = {"rev": 0, "city": "", "lat": 0.0, "lon": 0.0,
               "night_start": "22:30", "night_end": "06:30",
               "night_clock_only": False, "remind_sound": True,
               "todos_min": 60, "weather_min": 60}
        atomic_write(CONFIG_FILE, json.dumps(cfg, ensure_ascii=False, indent=2))
    else:
        # 迁移：旧版默认同步过快，统一调整为每小时
        cfg = load_json(CONFIG_FILE, {})
        changed = False
        try:
            if int(cfg.get("todos_min", 60) or 60) < 60:
                cfg["todos_min"] = 60
                changed = True
        except (TypeError, ValueError):
            pass
        try:
            if int(cfg.get("weather_min", 60) or 60) < 60:
                cfg["weather_min"] = 60
                changed = True
        except (TypeError, ValueError):
            pass
        if changed:
            atomic_write(CONFIG_FILE, json.dumps(cfg, ensure_ascii=False, indent=2))
            print("* 已将待办/天气刷新间隔调整为每小时（可通过设置页推送修改）")


def read_token():
    with open(TOKEN_FILE, "r", encoding="utf-8") as f:
        return f.read().strip()


# ---------- 用户库与会话（网页后台登录） ----------

def hash_password(password, salt=None):
    if salt is None:
        salt = secrets.token_hex(16)
    h = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                            bytes.fromhex(salt), _PBKDF2_ITER)
    return {"salt": salt, "hash": h.hex(), "iterations": _PBKDF2_ITER}


def verify_password(user, password):
    try:
        salt = bytes.fromhex(user.get("salt", ""))
        h = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                                salt, int(user.get("iterations", _PBKDF2_ITER)))
        return hmac.compare_digest(h.hex(), user.get("hash", ""))
    except Exception:
        return False


def load_users():
    d = load_json(USERS_FILE, {"users": []})
    return d if isinstance(d, dict) and isinstance(d.get("users"), list) else {"users": []}


def save_users(users):
    atomic_write(USERS_FILE, json.dumps(users, ensure_ascii=False, indent=2))


def load_sessions():
    d = load_json(SESSIONS_FILE, {"sessions": {}})
    now = time.time()
    out = {}
    if isinstance(d, dict):
        for sid, s in (d.get("sessions") or {}).items():
            if isinstance(s, dict) and now - float(s.get("ts", 0)) < SESSION_TTL:
                out[sid] = s
    return out


def save_sessions(sessions):
    atomic_write(SESSIONS_FILE, json.dumps({"sessions": sessions}))


def create_session(username, role):
    with _SAVE_LOCK:
        sessions = load_sessions()
        sid = secrets.token_hex(32)
        sessions[sid] = {"user": username, "role": role, "ts": time.time()}
        save_sessions(sessions)
    return sid


def drop_session(sid):
    with _SAVE_LOCK:
        sessions = load_sessions()
        if sid in sessions:
            del sessions[sid]
            save_sessions(sessions)


def drop_user_sessions(username):
    with _SAVE_LOCK:
        sessions = load_sessions()
        remain = {k: v for k, v in sessions.items() if v.get("user") != username}
        if len(remain) != len(sessions):
            save_sessions(remain)


def load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def atomic_write(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def get_todo_rev():
    st = load_json(STATE_FILE, {"todo_rev": 0})
    try:
        return int(st.get("todo_rev", 0))
    except (TypeError, ValueError):
        return 0


def validate_todos(doc):
    if not isinstance(doc, dict) or not isinstance(doc.get("todos"), list):
        raise ValueError("数据缺少 todos 数组")
    out = []
    for i, t in enumerate(doc["todos"]):
        if not isinstance(t, dict) or not str(t.get("title", "")).strip():
            raise ValueError("第 %d 条缺少标题" % (i + 1))
        if not isinstance(t.get("date"), str) or len(t["date"]) != 10:
            raise ValueError("第 %d 条日期格式应为 yyyy-MM-dd" % (i + 1))
        if not isinstance(t.get("time"), str) or len(t["time"]) != 5:
            raise ValueError("第 %d 条时间格式应为 HH:mm" % (i + 1))
        rep = t.get("repeat", "none")
        if rep not in REPEATS:
            raise ValueError("第 %d 条 repeat 取值不合法" % (i + 1))
        item = {"id": str(t.get("id") or ("t%d" % (i + 1)))[:64],
                "title": str(t["title"]).strip()[:200],
                "date": t["date"], "time": t["time"],
                "remind": max(-1, min(int(t.get("remind", 0) or 0), 525600)),
                "repeat": rep}
        if rep != "none":
            item["interval"] = max(1, int(t.get("interval", 1) or 1))
        if rep == "weekly" and isinstance(t.get("weekdays"), list) and t["weekdays"]:
            item["weekdays"] = [int(w) for w in t["weekdays"] if 1 <= int(w) <= 7]
        if rep == "hourly":
            item["window_from"] = str(t.get("window_from") or "08:00")
            item["window_to"] = str(t.get("window_to") or "22:00")
        if isinstance(t.get("end"), str) and len(t["end"]) == 10:
            item["end"] = t["end"]
        out.append(item)
    return {"version": 1, "todos": out}


# ---------- 天气代理：高德优先，Open-Meteo 兜底 ----------

WX_TTL = 600  # 缓存 10 分钟，个人 Key 免费配额足够
_WX_CACHE = {"ts": 0.0, "data": None}
_WX_LOCK = threading.Lock()


def _num(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return 0.0


def _http_json(url, timeout=8):
    req = urllib.request.Request(url, headers={"User-Agent": "ClockDash/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def _wmo_text(c):
    try:
        c = int(c)
    except (TypeError, ValueError):
        return "—"
    if c == 0:
        return "晴"
    if c in (1, 2):
        return "多云"
    if c == 3:
        return "阴"
    if c in (45, 48):
        return "雾"
    if 51 <= c <= 57:
        return "毛毛雨"
    if 61 <= c <= 67:
        return "雨"
    if 71 <= c <= 77:
        return "雪"
    if 80 <= c <= 82:
        return "阵雨"
    if c in (85, 86):
        return "阵雪"
    if c >= 95:
        return "雷阵雨"
    return "—"


def _amap_wind(direction, power):
    if not direction:
        return ""
    p = str(power or "")
    if "≤" in p:
        p = p.replace("≤", "1-")
    return "%s风%s" % (direction, p)


def _fetch_amap(key, adcode):
    url = ("https://api.amap.com/v3/weather/weatherInfo?city=%s&key=%s"
           "&extensions=all&output=JSON" % (adcode, key))
    doc = _http_json(url)
    if str(doc.get("status")) != "1" or not doc.get("lives"):
        raise ValueError("高德返回: %s" % doc.get("info", "未知错误"))
    live = doc["lives"][0]
    casts = []
    fcs = doc.get("forecasts") or []
    if fcs:
        for c in fcs[0].get("casts", []):
            casts.append({"date": c.get("date", ""),
                          "cond": c.get("dayweather") or c.get("nightweather") or "",
                          "min": _num(c.get("nighttemp")),
                          "max": _num(c.get("daytemp"))})
    return {"ok": True, "source": "amap",
            "city": live.get("city") or "",
            "cond": live.get("weather") or "",
            "temp": _num(live.get("temperature")),
            "humidity": str(live.get("humidity") or ""),
            "wind": _amap_wind(live.get("winddirection"), live.get("windpower")),
            "casts": casts}


def _fetch_openmeteo(lat, lon):
    url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
           "&current_weather=true&daily=weathercode,temperature_2m_max,temperature_2m_min"
           "&timezone=auto&forecast_days=4" % (lat, lon))
    doc = _http_json(url)
    cur = doc.get("current_weather") or {}
    daily = doc.get("daily") or {}
    dates = daily.get("time") or []
    codes = daily.get("weathercode") or []
    mins = daily.get("temperature_2m_min") or []
    maxs = daily.get("temperature_2m_max") or []
    casts = []
    for i, d in enumerate(dates):
        casts.append({"date": d,
                      "cond": _wmo_text(codes[i] if i < len(codes) else None),
                      "min": mins[i] if i < len(mins) else 0,
                      "max": maxs[i] if i < len(maxs) else 0})
    return {"ok": True, "source": "openmeteo", "city": "",
            "cond": _wmo_text(cur.get("weathercode")),
            "temp": _num(cur.get("temperature")),
            "humidity": "", "wind": "", "casts": casts}


def get_weather():
    cfg = load_json(CONFIG_FILE, {})
    now = time.time()
    with _WX_LOCK:
        if _WX_CACHE["data"] and now - _WX_CACHE["ts"] < WX_TTL:
            return _WX_CACHE["data"]

    result = None
    key = str(cfg.get("amap_key") or "").strip()
    adcode = str(cfg.get("adcode") or "").strip()
    # 白名单校验，防止拼 URL 时被注入额外参数
    if key and adcode:
        if not re.fullmatch(r"[A-Za-z0-9]+", key):
            result = {"ok": False, "error": "高德Key格式不合法"}
        elif not re.fullmatch(r"\d{6}", adcode):
            result = {"ok": False, "error": "行政区划代码应为 6 位数字"}
        else:
            try:
                result = _fetch_amap(key, adcode)
            except Exception as e:
                result = {"ok": False, "error": "高德: %s" % e}
        try:
            result = _fetch_amap(key, adcode)
        except Exception as e:
            result = {"ok": False, "error": "高德: %s" % e}

    if result is None or result.get("ok") is False:
        lat, lon = cfg.get("lat"), cfg.get("lon")
        if lat and lon:
            try:
                result = _fetch_openmeteo(lat, lon)
            except Exception as e:
                if result is None:
                    result = {"ok": False, "error": "Open-Meteo: %s" % e}

    if result is None:
        result = {"ok": False, "error": "未配置天气源：请在设置页填高德Key并选城市，或搜索经纬度"}

    if result.get("ok"):
        with _WX_LOCK:
            _WX_CACHE.update({"ts": now, "data": result})
    return result


# ---------- 法定节假日/调休（数据源：holiday-cn，按国务院公告年度更新） ----------

_HOLIDAY = {"ts": 0.0, "days": None}


def _holiday_year(year):
    url = "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json" % year
    doc = _http_json(url, timeout=15)
    days = doc.get("days") or []
    return [{"name": str(d.get("name", "")),
             "date": str(d.get("date", "")),
             "isOffDay": bool(d.get("isOffDay"))}
            for d in days if d.get("date")]


def get_holidays():
    now = time.time()
    with _WX_LOCK:
        if _HOLIDAY["days"] is not None and now - _HOLIDAY["ts"] < 86400:
            return {"ok": True, "days": _HOLIDAY["days"]}
    year = time.localtime().tm_year
    days = []
    ok = False
    for y in (year, year + 1):
        try:
            days.extend(_holiday_year(y))
            ok = True
        except Exception:
            pass
    if not ok:
        cached = load_json(HOLIDAY_CACHE_FILE, None)
        if cached and cached.get("days"):
            return {"ok": True, "days": cached["days"]}
        return {"ok": False, "error": "节假日数据获取失败"}
    with _WX_LOCK:
        _HOLIDAY["ts"] = now
        _HOLIDAY["days"] = days
    try:
        atomic_write(HOLIDAY_CACHE_FILE, json.dumps({"days": days}, ensure_ascii=False))
    except Exception:
        pass
    return {"ok": True, "days": days}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        SimpleHTTPRequestHandler.__init__(self, *args, directory=WEB_DIR, **kwargs)

    # ---------- 鉴权：设备口令或网页会话，返回 {"role": ...} 或 None ----------
    def _auth(self):
        qs = parse_qs(urlparse(self.path).query)
        tok = (qs.get("token") or [""])[0] or self.headers.get("X-Token", "")
        if tok:
            if hmac.compare_digest(tok, read_token()):
                return {"mode": "token", "role": "admin", "user": "device"}
            time.sleep(random.uniform(0.2, 0.5))
            return None
        sid = self.headers.get("X-Session", "")
        if sid:
            s = load_sessions().get(sid)
            if s:
                return {"mode": "session", "role": s.get("role", "user"),
                        "user": s.get("user", ""), "sid": sid}
            time.sleep(random.uniform(0.2, 0.4))
        return None

    def _handle_login(self, doc):
        username = str(doc.get("username", "")).strip()
        password = str(doc.get("password", ""))
        u = None
        for x in load_users()["users"]:
            if x.get("username") == username:
                u = x
                break
        if u is None or not verify_password(u, password):
            time.sleep(random.uniform(0.3, 0.8))
            return self._send_json({"error": "用户名或口令错误"}, 401)
        role = u.get("role", "user")
        sid = create_session(username, role)
        return self._send_json({"ok": True, "session": sid,
                                "role": role, "username": username})

    def _handle_users(self, doc):
        act = str(doc.get("action", ""))
        username = str(doc.get("username", "")).strip()
        password = str(doc.get("password", ""))
        if act == "list":
            return self._send_json({"ok": True,
                                    "users": [{"username": u.get("username"),
                                               "role": u.get("role", "user")}
                                              for u in load_users()["users"]]})
        if not re.fullmatch(r"[A-Za-z0-9_]{2,32}", username):
            return self._send_json({"error": "用户名需为 2-32 位字母数字下划线"}, 400)
        users = load_users()
        idx = next((i for i, u in enumerate(users["users"])
                    if u.get("username") == username), -1)
        if act == "add":
            if idx >= 0:
                return self._send_json({"error": "用户已存在"}, 400)
            if len(password) < 6:
                return self._send_json({"error": "口令至少 6 位"}, 400)
            role = doc.get("role") if doc.get("role") in ("admin", "user") else "user"
            u = hash_password(password)
            users["users"].append({"username": username, "role": role, **u})
        elif act == "passwd":
            if idx < 0:
                return self._send_json({"error": "用户不存在"}, 404)
            if len(password) < 6:
                return self._send_json({"error": "口令至少 6 位"}, 400)
            users["users"][idx].update(hash_password(password))
            drop_user_sessions(username)  # 改密后踢掉旧会话
        elif act == "del":
            if idx < 0:
                return self._send_json({"error": "用户不存在"}, 404)
            if username == "admin":
                return self._send_json({"error": "不能删除内置管理员"}, 400)
            target = users["users"][idx]
            admins = sum(1 for x in users["users"] if x.get("role") == "admin")
            if target.get("role") == "admin" and admins <= 1:
                return self._send_json({"error": "不能删除最后一个管理员"}, 400)
            del users["users"][idx]
            drop_user_sessions(username)
        else:
            return self._send_json({"error": "未知操作"}, 400)
        save_users(users)
        return self._send_json({"ok": True})

    def _send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---------- 路由 ----------
    def do_GET(self):
        p = urlparse(self.path).path
        if p in ("/api/todos", "/api/config", "/api/ping", "/api/weather",
                 "/api/holidays", "/api/check", "/api/users"):
            a = self._auth()
            if a is None:
                return self._send_json({"error": "未登录"}, 401)
            if p == "/api/users":
                if a.get("role") != "admin":
                    return self._send_json({"error": "需要管理员权限"}, 403)
                return self._send_json({"ok": True,
                                        "users": [{"username": u.get("username"),
                                                   "role": u.get("role", "user")}
                                                  for u in load_users()["users"]]})
            if p == "/api/todos":
                return self._send_json(load_json(TODOS_FILE, {"version": 1, "todos": []}))
            if p == "/api/config":
                cfg = load_json(CONFIG_FILE, {})
                # 平板不需要高德 Key，默认剥离；管理员带 full=1 才返回
                qs = parse_qs(urlparse(self.path).query)
                if (qs.get("full") or [""])[0] == "1" and a.get("role") != "admin":
                    return self._send_json({"error": "需要管理员权限"}, 403)
                if (qs.get("full") or [""])[0] != "1":
                    cfg = dict(cfg)
                    cfg.pop("amap_key", None)
                return self._send_json(cfg)
            if p == "/api/weather":
                return self._send_json(get_weather())
            if p == "/api/holidays":
                return self._send_json(get_holidays())
            if p == "/api/check":
                cfg = load_json(CONFIG_FILE, {})
                return self._send_json({"ok": True,
                                        "todo_rev": get_todo_rev(),
                                        "config_rev": int(cfg.get("rev", 0) or 0)})
            return self._send_json({"ok": True})
        if p == "/":
            p = "/index.html"
        SimpleHTTPRequestHandler.do_GET(self)

    def do_POST(self):
        p = urlparse(self.path).path
        if p not in ("/api/todos", "/api/config", "/api/login", "/api/logout", "/api/users"):
            return self.send_error(404)
        a = self._auth()
        if p in ("/api/todos", "/api/config", "/api/users") and a is None:
            return self._send_json({"error": "未登录"}, 401)
        if p == "/api/users" and a.get("role") != "admin":
            return self._send_json({"error": "需要管理员权限"}, 403)
        try:
            n = int(self.headers.get("Content-Length", 0) or 0)
            if n > 1024 * 1024:
                return self._send_json({"error": "请求体过大"}, 413)
            doc = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
            if p == "/api/login":
                return self._handle_login(doc)
            if p == "/api/logout":
                sid = self.headers.get("X-Session", "")
                if sid:
                    drop_session(sid)
                return self._send_json({"ok": True})
            if p == "/api/users":
                return self._handle_users(doc)
            if p == "/api/todos":
                out = validate_todos(doc)
                with _SAVE_LOCK:
                    rev = get_todo_rev() + 1
                    out["rev"] = rev
                    atomic_write(STATE_FILE, json.dumps({"todo_rev": rev}))
                    atomic_write(TODOS_FILE, json.dumps(out, ensure_ascii=False, indent=2))
                return self._send_json({"ok": True, "rev": rev})
            cfg = load_json(CONFIG_FILE, {})
            for k in ("city", "lat", "lon", "night_start", "night_end",
                      "night_clock_only", "remind_sound", "todos_min", "weather_min",
                      "amap_key", "adcode"):
                if k in doc:
                    cfg[k] = doc[k]
            cfg["rev"] = int(cfg.get("rev", 0)) + 1
            atomic_write(CONFIG_FILE, json.dumps(cfg, ensure_ascii=False, indent=2))
            return self._send_json({"ok": True, "rev": cfg["rev"]})
        except ValueError as e:
            return self._send_json({"error": str(e)}, 400)
        except Exception as e:
            return self._send_json({"error": "保存失败: %s" % e}, 500)

    def end_headers(self):
        # 设置页更新后手机端立即生效，避免缓存旧页面
        self.send_header("Cache-Control", "no-store")
        SimpleHTTPRequestHandler.end_headers(self)

    def log_message(self, fmt, *args):
        # 只记路径，不记 query —— 避免口令出现在 URL 里被写进日志
        sys.stderr.write("%s %s %s\n" % (self.address_string(), self.command,
                                         urlparse(self.path).path))


def main():
    ensure_data()
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    token = read_token()
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("=" * 60)
    print(" 时钟看板后台已启动，端口 %d" % port)
    print(" 手机打开设置页: http://<本机IP>:%d/?token=%s" % (port, token))
    print(" 口令保存在: %s" % TOKEN_FILE)
    print(" 按 Ctrl+C 停止")
    print("=" * 60)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
