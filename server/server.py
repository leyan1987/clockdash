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
                     附带 server_time 与服务器主动下发的 notice 留言
  GET  /api/history  历史待办查询（已过期发生时刻+已删除规则归档，需口令/会话）
  GET  /api/watchdog 设备在线状态 + 看护配置 + 告警历史（仅管理员）
  POST /api/watchdog 保存看护配置（仅管理员）
  POST /api/watchdog/test  立即发送一条测试通知（仅管理员）
  POST /api/message  服务器主动给设备留言，设备下次 /api/check 时收到（需口令/会话）

在线看护：设备每次用 X-Token 访问接口都会更新 last_seen；看门狗线程每 30 秒
检查一次，超过阈值无通讯即判定失联，主动经 webhook 推送告警（企业微信/钉钉/
飞书/自定义），恢复时再推一条。服务器无法反向连接 NAT 后的平板，留言通过设备
下一次轮询带回，属于"离线消息补发"，无需改平板、不增加耗电。

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
from datetime import datetime, timedelta
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
DEVICES_FILE = os.path.join(DATA_DIR, "devices.json")
WATCH_CONFIG_FILE = os.path.join(DATA_DIR, "watchdog-config.json")
WATCH_STATE_FILE = os.path.join(DATA_DIR, "watchdog-state.json")

SESSION_TTL = 30 * 86400   # 网页登录会话有效期 30 天
_PBKDF2_ITER = 100000      # 口令哈希迭代次数
_SERVER_START = time.time()  # 用于避免服务刚重启、平板还没首次上报时的误报

REPEATS = ("none", "hourly", "daily", "weekly", "monthly", "yearly")

_SAVE_LOCK = threading.RLock()  # 可重入：bump_todo_rev 会在外层持锁时被调用


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
    else:
        # 迁移：普通用户补设备绑定字段（历史数据统一绑到默认设备）
        us = load_users()
        changed = False
        for u in us["users"]:
            if u.get("role") == "user" and not u.get("device"):
                u["device"] = "default"
                changed = True
        if changed:
            save_users(us)
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
    if load_devices() is None:
        # 多设备迁移：现有口令/待办/配置整体成为“默认平板”
        devs = {"devices": [{"id": "default", "name": "默认平板", "token": read_token()}]}
        atomic_write(DEVICES_FILE, json.dumps(devs, ensure_ascii=False, indent=2))
        for kind, src in (("todos", TODOS_FILE), ("config", CONFIG_FILE)):
            dst = os.path.join(DATA_DIR, "%s_default.json" % kind)
            if os.path.exists(src) and not os.path.exists(dst):
                try:
                    with open(src, "r", encoding="utf-8") as f:
                        atomic_write(dst, f.read())
                except Exception:
                    pass
        print("* 已启用多设备：原平板迁移为默认设备（default）")


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


# ---------- 设备库（多平板：每台独立待办/配置/口令） ----------

def load_devices():
    d = load_json(DEVICES_FILE, None)
    if isinstance(d, dict) and isinstance(d.get("devices"), list) and d["devices"]:
        return d
    return None


def device_by_id(devid):
    if not isinstance(devid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", devid):
        return None
    for d in load_devices()["devices"]:
        if d.get("id") == devid:
            return d
    return None


def device_by_token(tok):
    for d in load_devices()["devices"]:
        if hmac.compare_digest(str(d.get("token", "")), tok):
            return d
    # 兼容：旧全局口令视为默认设备
    try:
        if hmac.compare_digest(read_token(), tok):
            return {"id": "default", "name": "默认平板", "token": tok}
    except Exception:
        pass
    return None


def device_token(dev_id):
    for d in load_devices()["devices"]:
        if d.get("id") == dev_id:
            return str(d.get("token", "") or "")
    return ""


def token_group(dev_id):
    """同一口令的所有设备（口令即设备组）：一次推送，全组生效。
    场景：多个平板填了同一个设备口令，希望改一份配置/待办全都同步。"""
    tok = device_token(dev_id)
    if not tok:
        return [dev_id]
    ids = [d.get("id") for d in load_devices()["devices"]
           if str(d.get("token", "") or "") == tok]
    return ids if dev_id in ids else ids + [dev_id]


def todos_path(dev_id):
    return os.path.join(DATA_DIR, "todos_%s.json" % dev_id)


def config_path(dev_id):
    return os.path.join(DATA_DIR, "config_%s.json" % dev_id)


def history_path(dev_id):
    return os.path.join(DATA_DIR, "history_%s.json" % dev_id)


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


# ---------- 在线看护：设备心跳 / 失联告警 / 服务器主动留言 ----------
#
# 背景：平板在内网 NAT 后、无固定 IP，服务器无法反向建立连接，所以做不到
# TCP 意义上的"真推送"。这里用三层组合达到同等效果：
#   1) 设备每次用 X-Token 访问任意接口 → 记 last_seen（复用现有 5 分钟轮询，零改动）；
#   2) 看门狗线程定时判定失联 → 服务器主动把告警推到手机（webhook）；
#   3) 管理员可给设备留言 → 排队等设备下一次 /api/check 带回（离线消息补发）。
# 若将来要秒级送达，可再加 /api/wait 长轮询，但老平板保持长连接耗电明显，默认不开。

WATCH_DEFAULT = {
    "enabled": True,
    "offline_after_min": 15,   # 正常 5 分钟一次 check，15 分钟没来即视为失联
    "repeat_min": 0,           # 0=只在状态翻转时提醒一次；>0=持续离线时每 N 分钟重复
    "notify_recover": True,
    "webhook_type": "wecom",   # wecom / dingtalk / feishu / custom
    "webhook_url": "",
}


def load_watch_config():
    c = load_json(WATCH_CONFIG_FILE, {})
    out = dict(WATCH_DEFAULT)
    if isinstance(c, dict):
        for k, v in c.items():
            if k in WATCH_DEFAULT:
                out[k] = v
    # 收敛到合法范围，避免后台误填导致看门狗乱报
    for k, lo, hi, dft in (("offline_after_min", 6, 1440, 15),
                           ("repeat_min", 0, 1440, 0)):
        try:
            out[k] = max(lo, min(hi, int(out[k])))
        except (TypeError, ValueError):
            out[k] = dft
    out["enabled"] = bool(out["enabled"])
    out["notify_recover"] = bool(out["notify_recover"])
    out["webhook_type"] = str(out["webhook_type"] or "wecom")
    out["webhook_url"] = str(out["webhook_url"] or "").strip()
    return out


def save_watch_config(c):
    with _SAVE_LOCK:
        atomic_write(WATCH_CONFIG_FILE, json.dumps(c, ensure_ascii=False, indent=2))


def load_watch_state():
    s = load_json(WATCH_STATE_FILE, None)
    if not isinstance(s, dict):
        s = {}
    for k, v in (("devices", {}), ("alerts", {}), ("messages", {}), ("history", [])):
        if not isinstance(s.get(k), type(v)):
            s[k] = v
    return s


def save_watch_state(s):
    with _SAVE_LOCK:
        s["history"] = s.get("history", [])[-100:]  # 只留最近 100 条，防止无限增长
        atomic_write(WATCH_STATE_FILE, json.dumps(s, ensure_ascii=False, indent=2))


def touch_device(dev_id, api_path, ip):
    """设备用 X-Token 访问任意接口都算一次心跳。
    网页会话访问一律不算，否则管理员打开后台会把掉线的平板刷成在线。"""
    if not dev_id:
        return
    now = time.time()
    with _SAVE_LOCK:
        s = load_watch_state()
        d = s["devices"].setdefault(dev_id, {})
        d["last_seen"] = now
        d["last_api"] = api_path
        d["last_ip"] = ip
        d.setdefault("first_seen", now)
        d["hits"] = int(d.get("hits", 0) or 0) + 1
        save_watch_state(s)


def _fmt_duration(sec):
    sec = int(max(0, sec))
    if sec < 60:
        return "%d 秒" % sec
    if sec < 3600:
        return "%d 分钟" % (sec // 60)
    if sec < 86400:
        return "%d 小时 %d 分钟" % (sec // 3600, (sec % 3600) // 60)
    return "%d 天 %d 小时" % (sec // 86400, (sec % 86400) // 3600)


def _post_webhook(url, wtype, title, text):
    if not url or not url.lower().startswith(("http://", "https://")):
        return "未配置合法的 http(s) webhook 地址"
    content = title + "\n" + text
    if wtype in ("wecom", "dingtalk"):
        body = {"msgtype": "text", "text": {"content": content}}
    elif wtype == "feishu":
        body = {"msg_type": "text", "content": {"text": content}}
    else:  # custom：通用 JSON，便于接 n8n / Home Assistant / 自建服务
        body = {"title": title, "text": text, "source": "ClockDash"}
    req = urllib.request.Request(
        url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=8) as r:
        r.read(2048)
    return ""


def watch_notify(kind, dev_name, text, push=True):
    """记一条告警历史，并（可选）主动把消息推到 webhook。
    webhook 在独立线程里发送，网络挂住不会拖住看门狗或 HTTP 请求。"""
    rec = {"ts": time.time(), "kind": kind, "device": dev_name, "text": text}
    with _SAVE_LOCK:
        s = load_watch_state()
        s["history"].append(rec)
        save_watch_state(s)
    title = {"offline": "⚠ 平板失联", "online": "✓ 平板恢复",
             "test": "测试通知", "message": "服务器留言"}.get(kind, "ClockDash 通知")
    sys.stderr.write("[watchdog] %s | %s | %s\n" % (title, dev_name, text))
    if push:
        c = load_watch_config()
        if c["webhook_url"]:
            def _send():
                try:
                    err = _post_webhook(c["webhook_url"], c["webhook_type"], title, text)
                    if err:
                        sys.stderr.write("[watchdog] webhook: %s\n" % err)
                except Exception as e:
                    sys.stderr.write("[watchdog] webhook error: %s\n" % e)
            threading.Thread(target=_send, daemon=True).start()
    return rec


def watchdog_tick():
    """每轮扫描所有设备，只在 online↔offline 状态翻转（或到达重复提醒间隔）时告警。"""
    c = load_watch_config()
    if not c["enabled"]:
        return
    now = time.time()
    limit = c["offline_after_min"] * 60
    repeat = c["repeat_min"] * 60
    events = []
    with _SAVE_LOCK:
        s = load_watch_state()
        changed = False
        for d in load_devices()["devices"]:
            dev_id = d.get("id")
            name = d.get("name") or dev_id
            last = float(s["devices"].get(dev_id, {}).get("last_seen", 0) or 0)
            al = s["alerts"].setdefault(dev_id, {})
            # 服务刚重启且这台设备从未上报过：给它一个完整阈值窗口，别一启动就误报
            if last <= 0 and (now - _SERVER_START) < limit:
                continue
            offline = last <= 0 or (now - last) > limit
            was = bool(al.get("offline", False))
            if offline and not was:
                al.update({"offline": True, "since": now, "last_notify": now})
                changed = True
                if last > 0:
                    text = "「%s」距上次通讯已 %s" % (name, _fmt_duration(now - last))
                else:
                    text = "「%s」没有任何上线记录，可能从未成功连上服务器" % name
                text += "（阈值 %d 分钟）。请检查平板网络、供电与时钟看板是否在运行。" \
                        % c["offline_after_min"]
                events.append(("offline", name, text))
            elif offline and was and repeat > 0 \
                    and now - float(al.get("last_notify", 0) or 0) > repeat:
                al["last_notify"] = now
                changed = True
                events.append(("offline", name,
                               "「%s」仍处于失联状态，已持续 %s。"
                               % (name, _fmt_duration(now - last))))
            elif not offline and was:
                since = float(al.get("since", now) or now)
                al.update({"offline": False, "recovered_at": now})
                changed = True
                if c["notify_recover"]:
                    events.append(("online", name,
                                   "「%s」已重新上线，中断约 %s。"
                                   % (name, _fmt_duration(now - since))))
        # 清掉已过期的留言
        for k in list(s["messages"].keys()):
            keep = [m for m in s["messages"][k]
                    if float(m.get("expires", 0) or 0) > now]
            if len(keep) != len(s["messages"][k]):
                changed = True
            if keep:
                s["messages"][k] = keep
            else:
                del s["messages"][k]
        if changed:
            save_watch_state(s)
    # 锁外再发通知：watch_notify 自己也要写状态文件，避免锁内嵌套等待
    for kind, name, text in events:
        threading.Thread(target=watch_notify, args=(kind, name, text), daemon=True).start()


def watchdog_loop():
    # 启动后先等 20 秒，避开服务刚起来时设备尚未发起第一次 check 的窗口
    time.sleep(20)
    while True:
        try:
            watchdog_tick()
        except Exception as e:
            sys.stderr.write("[watchdog] tick error: %s\n" % e)
        time.sleep(30)


def watchdog_status():
    c = load_watch_config()
    now = time.time()
    s = load_watch_state()
    out = []
    for d in load_devices()["devices"]:
        dev_id = d.get("id")
        info = s["devices"].get(dev_id, {})
        al = s["alerts"].get(dev_id, {})
        last = float(info.get("last_seen", 0) or 0)
        offline = last <= 0 or (now - last) > c["offline_after_min"] * 60
        out.append({
            "id": dev_id,
            "name": d.get("name") or dev_id,
            "last_seen": last,
            "last_api": info.get("last_api", ""),
            "last_ip": info.get("last_ip", ""),
            "hits": int(info.get("hits", 0) or 0),
            "age_text": _fmt_duration(now - last) if last > 0 else "从未上线",
            "online": not offline,
            "watch_enabled": c["enabled"],
            "offline_since": float(al.get("since", 0) or 0),
            "pending_messages": len(s["messages"].get(dev_id, [])),
        })
    return {"ok": True, "config": c, "devices": out,
            "history": list(reversed(s.get("history", [])[-30:])),
            "server_time": now, "checked_every_sec": 30}


def peek_device_notice(dev_id):
    """取该设备最新一条未过期的服务器留言，随 /api/check 带回（离线消息补发）。
    不主动删除：平板端目前没有 ack 机制，重复下发直到过期更安全，客户端可用
    notice.id 自行去重。"""
    now = time.time()
    with _SAVE_LOCK:
        s = load_watch_state()
        old = s["messages"].get(dev_id, [])
        msgs = [m for m in old if float(m.get("expires", 0) or 0) > now]
        if len(msgs) != len(old):
            if msgs:
                s["messages"][dev_id] = msgs
            else:
                s["messages"].pop(dev_id, None)
            save_watch_state(s)
    if not msgs:
        return None
    m = msgs[-1]
    return {"id": m.get("id", ""), "text": m.get("text", ""), "ts": m.get("ts", 0)}


def get_todo_rev(dev_id):
    st = load_json(STATE_FILE, {})
    v = st.get("todo_rev", 0)
    if isinstance(v, dict):
        try:
            return int(v.get(dev_id, 0))
        except (TypeError, ValueError):
            return 0
    # 旧格式：全局整数视为默认设备的版本号
    try:
        return int(v) if dev_id == "default" else 0
    except (TypeError, ValueError):
        return 0


def set_todo_rev(dev_id, val):
    with _SAVE_LOCK:
        st = load_json(STATE_FILE, {})
        v = st.get("todo_rev", 0)
        if not isinstance(v, dict):
            v = {"default": int(v) if isinstance(v, int) else 0}
        v[dev_id] = int(val)
        atomic_write(STATE_FILE, json.dumps({"todo_rev": v}))


def bump_todo_rev(dev_id):
    with _SAVE_LOCK:
        st = load_json(STATE_FILE, {})
        v = st.get("todo_rev", 0)
        if not isinstance(v, dict):
            v = {"default": int(v) if isinstance(v, int) else 0}
        nxt = int(v.get(dev_id, 0)) + 1
        set_todo_rev(dev_id, nxt)
        return nxt


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


# ---------- 历史待办：与平板端 Recur.java 同语义的规则展开 ----------

_HIST_CAP = 300        # 单条规则展开上限
_HIST_ARCHIVE = 500    # 已删除规则归档条数上限


def _month_max(y, m):
    if m == 12:
        nxt = datetime(y + 1, 1, 1)
    else:
        nxt = datetime(y, m + 1, 1)
    return (nxt - timedelta(days=1)).day


def _dow_mon1(c):
    return c.isoweekday()  # 周一=1 … 周日=7


def _week_index(c):
    return ((c - datetime(1970, 1, 5)).days) // 7


def expand_rule(t, frm, to):
    """展开规则 t 在 [frm, to] 内的发生时刻（升序 datetime 列表）。
    语义与平板端 Recur.java 一致（tests/recur_port_test.py 已对齐）。"""
    out = []
    date = str(t.get("date", ""))
    tstr = str(t.get("time", ""))
    if len(date) != 10 or len(tstr) < 4:
        return out
    try:
        anchor = datetime.strptime(date, "%Y-%m-%d")
    except ValueError:
        return out
    try:
        parts = tstr.split(":")
        hh = max(0, min(int(parts[0]), 23))
        mm = max(0, min(int(parts[1]), 59))
    except Exception:
        hh = mm = 0
    rep = t.get("repeat", "none")
    try:
        step = max(1, int(t.get("interval", 1) or 1))
    except Exception:
        step = 1
    end_limit = None
    if isinstance(t.get("end"), str) and len(t["end"]) == 10:
        try:
            end_limit = datetime.strptime(t["end"], "%Y-%m-%d") + timedelta(days=1)
        except ValueError:
            end_limit = None

    def make(day):
        return day.replace(hour=hh, minute=mm, second=0, microsecond=0)

    def add(occ):
        if occ < frm or occ > to:
            return True
        if end_limit is not None and occ >= end_limit:
            return False
        out.append(occ)
        return True

    if rep == "none":
        add(make(anchor))

    elif rep == "hourly":
        try:
            h0 = max(0, min(int(str(t.get("window_from", "08:00")).split(":")[0]), 23))
            h1 = max(0, min(int(str(t.get("window_to", "22:00")).split(":")[0]), 23))
        except Exception:
            h0, h1 = 8, 22
        if h1 < h0:
            h1 = h0
        day = max(anchor, frm.replace(hour=0, minute=0, second=0, microsecond=0))
        for _ in range(500):
            if day > to or len(out) >= _HIST_CAP:
                break
            for h in range(h0, h1 + 1, step):
                if len(out) >= _HIST_CAP:
                    break
                occ = day.replace(hour=h, minute=mm)
                if occ >= anchor:
                    add(occ)
            day += timedelta(days=1)

    elif rep == "daily":
        from_day = frm.replace(hour=0, minute=0, second=0, microsecond=0)
        c = anchor
        while c < from_day:
            c += timedelta(days=step)
        for _ in range(500):
            if c > to or len(out) >= _HIST_CAP:
                break
            if not add(make(c)):
                break
            c += timedelta(days=step)

    elif rep == "weekly":
        # 星期列表做安全转换（防手改数据文件注入异常值）
        wds = []
        for w in t.get("weekdays") or []:
            try:
                wv = int(w)
                if 1 <= wv <= 7:
                    wds.append(wv)
            except (TypeError, ValueError):
                pass
        if not wds:
            from_day = frm.replace(hour=0, minute=0, second=0, microsecond=0)
            c = anchor
            while c < from_day:
                c += timedelta(days=7 * step)
            for _ in range(500):
                if c > to or len(out) >= _HIST_CAP:
                    break
                if not add(make(c)):
                    break
                c += timedelta(days=7 * step)
        else:
            from_day = frm.replace(hour=0, minute=0, second=0, microsecond=0)
            c = max(from_day, anchor)
            anchor_week = _week_index(anchor)
            for _ in range(500):
                if c > to or len(out) >= _HIST_CAP:
                    break
                w = _week_index(c)
                if w >= anchor_week and (w - anchor_week) % step == 0 and c >= anchor \
                        and _dow_mon1(c) in wds:
                    add(make(c))
                c += timedelta(days=1)

    elif rep == "monthly":
        day, y0, m0 = anchor.day, anchor.year, anchor.month
        for k in range(240):
            if len(out) >= _HIST_CAP:
                break
            m = m0 + k * step
            y, m = y0 + (m - 1) // 12, (m - 1) % 12 + 1
            try:
                first = datetime(y, m, 1)
            except ValueError:
                continue
            if first > to + timedelta(days=31):
                break
            if day > _month_max(y, m):
                continue
            c = first.replace(day=day)
            if c < anchor:
                continue
            if not add(make(c)) and c > to:
                break

    elif rep == "yearly":
        day, month, y0 = anchor.day, anchor.month, anchor.year
        for k in range(60):
            if len(out) >= _HIST_CAP:
                break
            y = y0 + k * step
            try:
                first = datetime(y, month, 1)
            except ValueError:
                continue
            if first > to + timedelta(days=366):
                break
            if day > _month_max(y, month):
                continue
            c = first.replace(day=day)
            if c < anchor:
                continue
            if not add(make(c)) and c > to:
                break

    out.sort()
    return out


_REPEAT_TEXT = {"none": "不重复", "hourly": "每小时", "daily": "每天",
                "weekly": "每周", "monthly": "每月", "yearly": "每年"}


def get_history(dev_id, days, q, limit):
    try:
        days = max(1, min(int(days or 90), 3650))
    except (TypeError, ValueError):
        days = 90
    try:
        limit = max(1, min(int(limit or 300), 2000))
    except (TypeError, ValueError):
        limit = 300
    q = str(q or "").strip()
    now = datetime.now()
    start = now - timedelta(days=days)

    rules = []
    for t in load_json(todos_path(dev_id), {"todos": []}).get("todos", []):
        if isinstance(t, dict):
            rules.append(dict(t))
    # 被删除的规则归档同样纳入历史
    for t in load_json(history_path(dev_id), {"deleted": []}).get("deleted", []):
        if isinstance(t, dict):
            r = dict(t)
            r["_deleted"] = True
            rules.append(r)

    items = []
    for r in rules:
        title = str(r.get("title", ""))
        if q and q not in title:
            continue
        for occ in expand_rule(r, start, now):
            items.append({"time": occ.strftime("%Y-%m-%d %H:%M"),
                          "title": title,
                          "repeat": _REPEAT_TEXT.get(r.get("repeat", "none"), "不重复"),
                          "deleted": bool(r.get("_deleted"))})
    items.sort(key=lambda x: x["time"], reverse=True)
    return {"ok": True, "total": len(items), "items": items[:limit]}


# ---------- 天气代理：高德优先，Open-Meteo 兜底 ----------

WX_TTL = 600  # 缓存 10 分钟，个人 Key 免费配额足够
_WX_CACHE = {}  # dev_id -> {"ts": ..., "data": ...}（每台设备各自位置）
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
    # 高德实况与预报是互斥返回：extensions=base 才有 lives，extensions=all
    # 才有 forecasts（官方文档：两者各自"仅在请求对应类型时有返回"）。
    # 一次 all 请求里取不到 lives，必须分别请求再合并，否则实况+预报全挂。
    # 注意必须是 restapi.amap.com（官方 API 域名）。api.amap.com 是主站域名，
    # API 路径会 302 重定向到错误页，请求必然失败退回 Open-Meteo。
    base_url = ("https://restapi.amap.com/v3/weather/weatherInfo?city=%s&key=%s"
                "&output=JSON" % (adcode, key))
    live = {}
    casts = []
    try:
        doc = _http_json(base_url + "&extensions=base")
        if str(doc.get("status")) == "1" and doc.get("lives"):
            live = doc["lives"][0]
    except Exception:
        pass  # 实况失败不影响预报
    try:
        doc = _http_json(base_url + "&extensions=all")
        if str(doc.get("status")) == "1":
            fcs = doc.get("forecasts") or []
            if fcs:
                for c in fcs[0].get("casts", []):
                    casts.append({"date": c.get("date", ""),
                                  "cond": c.get("dayweather") or c.get("nightweather") or "",
                                  "min": _num(c.get("nighttemp")),
                                  "max": _num(c.get("daytemp"))})
    except Exception:
        pass  # 预报失败不影响实况
    if not live and not casts:
        raise ValueError("高德未返回有效数据（实况与预报均失败）")
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


def get_weather(dev_id):
    now = time.time()
    with _WX_LOCK:
        c = _WX_CACHE.get(dev_id)
        if c and c.get("data") and now - c["ts"] < WX_TTL:
            return c["data"]

    cfg = load_json(config_path(dev_id), {})
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
            _WX_CACHE[dev_id] = {"ts": now, "data": result}
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
            dev = device_by_token(tok)
            if dev:
                # 只有真正的平板请求算心跳：网页后台用同一个设备口令访问时会带
                # X-Client: web，必须排除，否则管理员打开后台就把离线平板刷成在线。
                if self.headers.get("X-Client", "") != "web":
                    touch_device(dev["id"], urlparse(self.path).path,
                                 self.client_address[0] if self.client_address else "")
                return {"mode": "device", "role": "admin",
                        "user": dev.get("name", "平板"), "dev": dev["id"]}
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

    def _resolve_dev(self, a):
        """确定请求针对的设备。
        设备口令：按口令解析；普通用户：服务端强制为其绑定的设备（不信任传参）；
        管理员：取 ?devid= 参数。"""
        if a.get("mode") == "device":
            return a.get("dev")
        if a.get("role") == "user":
            for u in load_users()["users"]:
                if u.get("username") == a.get("user"):
                    return u.get("device") or "default"
            return None
        qs = parse_qs(urlparse(self.path).query)
        devid = (qs.get("devid") or [""])[0]
        if device_by_id(devid):
            return devid
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
            entry = {"username": username, "role": role, **u}
            if role == "user":
                # 普通用户必须绑定一台设备，登录后只能管理这一台
                devid = str(doc.get("device", "")).strip()
                if device_by_id(devid) is None:
                    return self._send_json({"error": "请为该用户选择要绑定的设备"}, 400)
                entry["device"] = devid
            users["users"].append(entry)
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

    def _handle_watch_config(self, doc):
        c = load_watch_config()
        for k in ("enabled", "notify_recover"):
            if k in doc:
                c[k] = bool(doc[k])
        for k in ("offline_after_min", "repeat_min"):
            if k in doc:
                try:
                    c[k] = int(doc[k])
                except (TypeError, ValueError):
                    pass
        if "webhook_type" in doc:
            c["webhook_type"] = str(doc["webhook_type"] or "wecom")
        if "webhook_url" in doc:
            u = str(doc["webhook_url"] or "").strip()
            if u and not u.lower().startswith(("http://", "https://")):
                return self._send_json(
                    {"error": "webhook 地址必须以 http:// 或 https:// 开头"}, 400)
            c["webhook_url"] = u
        save_watch_config(c)
        return self._send_json({"ok": True, "config": load_watch_config()})

    def _handle_message(self, doc, dev):
        """管理员主动给平板留言：写入队列，等设备下一次 /api/check 取走。"""
        text = str(doc.get("text", "") or "").strip()[:200]
        if not text:
            return self._send_json({"error": "留言内容不能为空"}, 400)
        try:
            hours = max(1, min(168, int(doc.get("ttl_hours", 24))))
        except (TypeError, ValueError):
            hours = 24
        now = time.time()
        m = {"id": secrets.token_hex(6), "text": text, "ts": now,
             "expires": now + hours * 3600}
        with _SAVE_LOCK:
            s = load_watch_state()
            s["messages"].setdefault(dev, []).append(m)
            s["messages"][dev] = s["messages"][dev][-10:]
            save_watch_state(s)
        # 留言本身不推手机，只进历史，避免发一条公告把管理员手机吵醒
        watch_notify("message", dev, "已给「%s」留言：%s" % (dev, text), push=False)
        return self._send_json({"ok": True, "message": m,
                                "hint": "设备下次检查（约 5 分钟内）即可收到"})

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
                 "/api/holidays", "/api/check", "/api/users", "/api/devices",
                 "/api/history", "/api/watchdog"):
            a = self._auth()
            if a is None:
                return self._send_json({"error": "未登录"}, 401)
            if p == "/api/watchdog":
                if a.get("role") != "admin":
                    return self._send_json({"error": "需要管理员权限"}, 403)
                return self._send_json(watchdog_status())
            if p == "/api/users":
                if a.get("role") != "admin":
                    return self._send_json({"error": "需要管理员权限"}, 403)
                return self._send_json({"ok": True,
                                        "users": [{"username": u.get("username"),
                                                   "role": u.get("role", "user"),
                                                   "device": u.get("device", "")}
                                                  for u in load_users()["users"]]})
            if p == "/api/devices":
                devs = load_devices()["devices"]
                if a.get("role") != "admin":
                    # 普通用户只能看到自己绑定的那一台设备（不含口令）
                    me = next((u for u in load_users()["users"]
                               if u.get("username") == a.get("user")), None)
                    mydev = (me.get("device", "default") if me else "default")
                    mine = [d for d in devs if d["id"] == mydev]
                    return self._send_json({"ok": True,
                                            "devices": [{"id": d["id"], "name": d.get("name", "")}
                                                        for d in mine]})
                return self._send_json({"ok": True,
                                        "devices": [{"id": d["id"], "name": d.get("name", ""),
                                                     "token": d.get("token", "")} for d in devs]})
            # 以下为设备级接口
            if p not in ("/api/ping", "/api/holidays"):
                dev = self._resolve_dev(a)
                if dev is None:
                    return self._send_json({"error": "未指定有效设备（缺 devid）"}, 400)
            if p == "/api/todos":
                return self._send_json(load_json(todos_path(dev), {"version": 1, "todos": []}))
            if p == "/api/config":
                cfg = load_json(config_path(dev), {})
                # 平板不需要高德 Key，默认剥离；管理员带 full=1 才返回
                qs = parse_qs(urlparse(self.path).query)
                if (qs.get("full") or [""])[0] == "1" and a.get("role") != "admin":
                    return self._send_json({"error": "需要管理员权限"}, 403)
                if (qs.get("full") or [""])[0] != "1":
                    cfg = dict(cfg)
                    cfg.pop("amap_key", None)
                return self._send_json(cfg)
            if p == "/api/weather":
                return self._send_json(get_weather(dev))
            if p == "/api/holidays":
                return self._send_json(get_holidays())
            if p == "/api/history":
                qs = parse_qs(urlparse(self.path).query)
                return self._send_json(get_history(dev,
                                                   (qs.get("days") or [90])[0],
                                                   (qs.get("q") or [""])[0],
                                                   (qs.get("limit") or [300])[0]))
            if p == "/api/check":
                cfg = load_json(config_path(dev), {})
                out = {"ok": True,
                       "todo_rev": get_todo_rev(dev),
                       "config_rev": int(cfg.get("rev", 0) or 0),
                       "server_time": int(time.time())}
                # 服务器"主动推送"给平板的消息：借设备既有的 5 分钟轮询捎带回去，
                # 设备即使刚刚失联过，一恢复上线就能收到（离线消息补发）。
                notice = peek_device_notice(dev)
                if notice:
                    out["notice"] = notice
                return self._send_json(out)
            return self._send_json({"ok": True})
        if p == "/":
            p = "/index.html"
        SimpleHTTPRequestHandler.do_GET(self)

    def do_POST(self):
        p = urlparse(self.path).path
        if p not in ("/api/todos", "/api/config", "/api/login", "/api/logout",
                     "/api/users", "/api/devices", "/api/watchdog",
                     "/api/watchdog/test", "/api/message"):
            return self.send_error(404)
        a = self._auth()
        if p not in ("/api/login", "/api/logout") and a is None:
            return self._send_json({"error": "未登录"}, 401)
        if p in ("/api/users", "/api/devices", "/api/watchdog",
                 "/api/watchdog/test") and a.get("role") != "admin":
            return self._send_json({"error": "需要管理员权限"}, 403)
        try:
            n = int(self.headers.get("Content-Length", 0) or 0)
            if n > 1024 * 1024:
                return self._send_json({"error": "请求体过大"}, 413)
            doc = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
            if not isinstance(doc, dict):
                doc = {}
            if p == "/api/login":
                return self._handle_login(doc)
            if p == "/api/logout":
                sid = self.headers.get("X-Session", "")
                if sid:
                    drop_session(sid)
                return self._send_json({"ok": True})
            if p == "/api/watchdog":
                return self._handle_watch_config(doc)
            if p == "/api/watchdog/test":
                watch_notify("test", "服务器", "测试通知：在线看护已生效，webhook 通道可用。")
                return self._send_json({"ok": True, "hint": "已写入告警历史并尝试推送 webhook"})
            if p == "/api/message":
                dev = self._resolve_dev(a)
                if dev is None:
                    return self._send_json({"error": "未指定有效设备（缺 devid）"}, 400)
                return self._handle_message(doc, dev)
            if p == "/api/users":
                return self._handle_users(doc)
            if p == "/api/devices":
                return self._handle_devices(doc)
            if p == "/api/todos":
                dev = self._resolve_dev(a)
                if dev is None:
                    return self._send_json({"error": "未指定有效设备（缺 devid）"}, 400)
                out = validate_todos(doc)
                with _SAVE_LOCK:
                    # 归档本次被删除的规则，供历史待办查询
                    old_list = load_json(todos_path(dev), {"todos": []}).get("todos", [])
                    new_ids = {x["id"] for x in out["todos"]}
                    hist = load_json(history_path(dev), {"deleted": []})
                    for t in old_list:
                        if isinstance(t, dict) and t.get("id") not in new_ids:
                            r = dict(t)
                            r["deleted_at"] = time.strftime("%Y-%m-%d %H:%M:%S")
                            hist.setdefault("deleted", []).append(r)
                    hist["deleted"] = hist["deleted"][-_HIST_ARCHIVE:]
                    atomic_write(history_path(dev), json.dumps(hist, ensure_ascii=False, indent=2))
                    # bump_todo_rev 内部也用 _SAVE_LOCK（RLock 可重入）
                    rev = bump_todo_rev(dev)
                    out["rev"] = rev
                    atomic_write(todos_path(dev), json.dumps(out, ensure_ascii=False, indent=2))
                    # 口令组：同口令的其他设备写入同一份待办并同步 rev，
                    # 它们的平板经 /api/check 发现变化后各拉取一次即一致
                    for gid in token_group(dev):
                        if gid == dev:
                            continue
                        try:
                            atomic_write(todos_path(gid), json.dumps(out, ensure_ascii=False, indent=2))
                            set_todo_rev(gid, rev)
                        except Exception:
                            pass
                return self._send_json({"ok": True, "rev": rev})
            # /api/config
            dev = self._resolve_dev(a)
            if dev is None:
                return self._send_json({"error": "未指定有效设备（缺 devid）"}, 400)
            cfg = load_json(config_path(dev), {})
            # 高德 Key/行政区划仅管理员可设置，普通用户推送时忽略这两项，
            # 防止网页端空白字段把管理员配置的 Key 冲掉
            keys = ("city", "lat", "lon", "night_start", "night_end",
                    "night_clock_only", "remind_sound", "todos_min", "weather_min",
                    "amap_key", "adcode")
            if a.get("role") != "admin":
                keys = tuple(k for k in keys if k not in ("amap_key", "adcode"))
            for k in keys:
                if k in doc:
                    cfg[k] = doc[k]
            cfg["rev"] = int(cfg.get("rev", 0)) + 1
            atomic_write(config_path(dev), json.dumps(cfg, ensure_ascii=False, indent=2))
            # 口令组：同口令的其他设备写入同一份配置（同 rev），一次推送全组生效
            for gid in token_group(dev):
                if gid == dev:
                    continue
                try:
                    atomic_write(config_path(gid), json.dumps(cfg, ensure_ascii=False, indent=2))
                except Exception:
                    pass
            return self._send_json({"ok": True, "rev": cfg["rev"]})
        except ValueError as e:
            return self._send_json({"error": str(e)}, 400)
        except Exception as e:
            return self._send_json({"error": "保存失败: %s" % e}, 500)

    def _handle_devices(self, doc):
        act = str(doc.get("action", ""))
        devices = load_devices()["devices"]
        if act == "add":
            name = str(doc.get("name", "")).strip()[:32] or "未命名平板"
            devid = "d" + secrets.token_hex(4)
            while any(d.get("id") == devid for d in devices):
                devid = "d" + secrets.token_hex(4)
            token = secrets.token_hex(16).upper()
            devices.append({"id": devid, "name": name, "token": token})
            atomic_write(todos_path(devid), json.dumps(
                {"version": 1, "rev": 0, "todos": []}, ensure_ascii=False, indent=2))
            atomic_write(config_path(devid), json.dumps(
                {"rev": 0, "city": "", "lat": 0.0, "lon": 0.0,
                 "night_start": "22:30", "night_end": "06:30",
                 "night_clock_only": False, "remind_sound": True,
                 "todos_min": 60, "weather_min": 60}, ensure_ascii=False, indent=2))
            save = {"devices": devices}
            atomic_write(DEVICES_FILE, json.dumps(save, ensure_ascii=False, indent=2))
            return self._send_json({"ok": True, "id": devid, "token": token, "name": name})
        devid = str(doc.get("id", ""))
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", devid or ""):
            return self._send_json({"error": "设备不存在"}, 404)
        dev = next((d for d in devices if d.get("id") == devid), None)
        if dev is None:
            return self._send_json({"error": "设备不存在"}, 404)
        if act == "rename":
            name = str(doc.get("name", "")).strip()[:32]
            if not name:
                return self._send_json({"error": "名称不能为空"}, 400)
            dev["name"] = name
        elif act == "del":
            if len(devices) <= 1:
                return self._send_json({"error": "至少保留一台设备"}, 400)
            # 有用户绑定此设备时禁止删除，避免用户失去管理目标
            bound = [u for u in load_users()["users"] if u.get("device") == devid]
            if bound:
                return self._send_json({"error": "用户 " + ", ".join(u["username"] for u in bound)
                                        + " 绑定在此设备上，请先删除或转移这些用户"}, 400)
            devices = [d for d in devices if d.get("id") != devid]
        else:
            return self._send_json({"error": "未知操作"}, 400)
        atomic_write(DEVICES_FILE, json.dumps({"devices": devices}, ensure_ascii=False, indent=2))
        return self._send_json({"ok": True})

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
    # 在线看护：独立线程，不占用 HTTP 请求线程；daemon 随主进程退出
    threading.Thread(target=watchdog_loop, name="clockdash-watchdog",
                     daemon=True).start()
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
