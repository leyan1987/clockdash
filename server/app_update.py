#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ClockDash 安卓端 OTA 发布工具（纯标准库）。

用法：
  python3 app_update.py publish legacy ClockDash-legacy-44.apk --name 1.44 --notes "修复xxx"
  python3 app_update.py publish modern ClockDash-modern-44.apk --name 1.44 --notes "修复xxx"
  python3 app_update.py list
  python3 app_update.py clear legacy    # 撤销该 flavor 的更新提示

说明：
  - APK 放到 server/apps/ 目录，manifest.json 记录 版本号/文件/MD5/大小/说明。
  - 文件名以 -<数字>.apk 结尾时 versionCode 可省略 --code（推荐命名习惯）。
  - 客户端每 5 分钟的 /api/check 会捎带发现新版本，自动下载并提示安装；
    完整性由 MD5（防下载损坏）+ APK 签名（系统安装器校验，防篡改）双保险。
  - 发布后无需重启服务器：manifest 与 APK 都是每次请求实时读盘。
"""

import argparse
import hashlib
import json
import os
import shutil
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
APPS_DIR = os.path.join(BASE, "apps")
MANIFEST = os.path.join(APPS_DIR, "manifest.json")


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def load_manifest():
    try:
        with open(MANIFEST, "r", encoding="utf-8") as f:
            m = json.load(f)
        return m if isinstance(m, dict) else {}
    except Exception:
        return {}


def save_manifest(m):
    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)
    os.replace(tmp, MANIFEST)


def cmd_publish(a):
    src = a.apk
    if not os.path.isfile(src):
        sys.exit("找不到文件: %s" % src)
    if a.flavor not in ("legacy", "modern"):
        sys.exit("flavor 必须是 legacy 或 modern")
    if a.code is None:
        stem = os.path.splitext(os.path.basename(src))[0]
        tail = stem.rsplit("-", 1)
        if len(tail) == 2 and tail[1].isdigit():
            a.code = int(tail[1])
        else:
            sys.exit("文件名不含版本号（需形如 xxx-44.apk），请用 --code 指定 versionCode")
    os.makedirs(APPS_DIR, exist_ok=True)
    # 统一命名为 <flavor>-<code>.apk：同版本重发直接覆盖
    dst_name = "%s-%d.apk" % (a.flavor, a.code)
    dst = os.path.join(APPS_DIR, dst_name)
    shutil.copyfile(src, dst)
    md5 = md5_of(dst)
    m = load_manifest()
    old = m.get(a.flavor) or {}
    m[a.flavor] = {"code": int(a.code), "name": str(a.name or a.code),
                   "file": dst_name, "md5": md5,
                   "size": os.path.getsize(dst),
                   "notes": a.notes or old.get("notes", "")}
    save_manifest(m)
    print("已发布 %s v%s (code=%d)" % (a.flavor, m[a.flavor]["name"], a.code))
    print("  文件: apps/%s  %d 字节  md5=%s" % (dst_name, m[a.flavor]["size"], md5))
    print("  客户端下次 /api/check（约 5 分钟内）即会收到更新提示")


def cmd_list(_a):
    m = load_manifest()
    if not m:
        print("apps/ 下还没有发布记录。用 publish 命令发布第一个安装包。")
        return
    for flavor in ("legacy", "modern"):
        e = m.get(flavor)
        if e:
            print("%-8s v%-6s code=%-4d %s (%d 字节, md5=%s)" % (
                flavor, e.get("name", "?"), e.get("code", 0),
                e.get("file", "?"), e.get("size", 0), e.get("md5", "?")[:12]))
            if e.get("notes"):
                print("         说明: %s" % e["notes"])


def cmd_clear(a):
    if a.flavor not in ("legacy", "modern"):
        sys.exit("flavor 必须是 legacy 或 modern")
    m = load_manifest()
    if m.pop(a.flavor, None) is None:
        print("%s 本就没有发布记录" % a.flavor)
        return
    save_manifest(m)
    print("已撤销 %s 的更新提示（客户端不会再收到该 flavor 的新版本通知）" % a.flavor)


def main():
    p = argparse.ArgumentParser(description="ClockDash OTA 发布工具")
    sub = p.add_subparsers(dest="cmd", required=True)
    pp = sub.add_parser("publish", help="发布一个 APK")
    pp.add_argument("flavor", help="legacy 或 modern")
    pp.add_argument("apk", help="APK 文件路径")
    pp.add_argument("--code", type=int, default=None, help="versionCode（文件名含 -<数字>.apk 时可省）")
    pp.add_argument("--name", default=None, help="versionName，如 1.44")
    pp.add_argument("--notes", default=None, help="更新说明（显示给用户）")
    pp.set_defaults(fn=cmd_publish)
    lp = sub.add_parser("list", help="查看已发布版本")
    lp.set_defaults(fn=cmd_list)
    cp = sub.add_parser("clear", help="撤销某 flavor 的更新提示")
    cp.add_argument("flavor")
    cp.set_defaults(fn=cmd_clear)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
