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
import struct
import sys
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
APPS_DIR = os.path.join(BASE, "apps")
MANIFEST = os.path.join(APPS_DIR, "manifest.json")


def apk_min_sdk(path):
    """解析 APK 内二进制 AndroidManifest.xml 的 uses-sdk@minSdkVersion。
    纯 Python 解析 AXML 字符串池与 START_ELEMENT 属性；解析失败返回 None。
    用于发布防呆：modern 包（minSdk>=21）绝不允许发布给 legacy（老平板）渠道。"""
    try:
        raw = zipfile.ZipFile(path).read("AndroidManifest.xml")
    except Exception:
        return None
    try:
        if struct.unpack_from("<H", raw, 0)[0] != 0x0003:   # 不是 RES_XML_TYPE
            return None
        off, n = 8, len(raw)
        strings = None
        min_sdk = None
        while off + 8 <= n:
            ctype, _hsize, size = struct.unpack_from("<HHI", raw, off)
            if size <= 0:
                break
            if ctype == 0x0001 and strings is None:
                # RES_STRING_POOL_TYPE 头 28 字节：type hsize size | scount styleCount flags stringsStart stylesStart
                hsize = struct.unpack_from("<H", raw, off + 2)[0]
                scount, _styles, sflags, sstart = struct.unpack_from("<IIII", raw, off + 8)
                utf8 = bool(sflags & 0x100)
                base = off + sstart
                strs = []
                for i in range(scount):
                    so = struct.unpack_from("<I", raw, off + hsize + i * 4)[0]
                    p = base + so
                    if utf8:
                        # u16字符数 与 u8字节数 都是 1-2 字节变长（高位续位）
                        l = raw[p]
                        p += 1
                        if l & 0x80:
                            l = ((l & 0x7F) << 8) | raw[p]
                            p += 1
                        l2 = raw[p]
                        p += 1
                        if l2 & 0x80:
                            l2 = ((l2 & 0x7F) << 8) | raw[p]
                            p += 1
                        strs.append(raw[p:p + l2].decode("utf-8", "replace"))
                    else:
                        l = struct.unpack_from("<H", raw, p)[0]
                        p += 2
                        if l & 0x8000:
                            l = ((l & 0x7FFF) << 16) | struct.unpack_from("<H", raw, p)[0]
                            p += 2
                        strs.append(raw[p:p + l * 2].decode("utf-16-le", "replace"))
                strings = strs
            elif ctype == 0x0102 and strings is not None:
                # START_ELEMENT：节点头16字节后是 attrExt{ns,name,attrStart,attrSize,attrCount}
                ename = struct.unpack_from("<I", raw, off + 20)[0]
                attr_start, attr_size, attr_count = struct.unpack_from("<HHH", raw, off + 24)
                elem = strings[ename] if ename < len(strings) else ""
                if elem == "uses-sdk":
                    abase = off + 16 + attr_start
                    for i in range(attr_count):
                        a = abase + i * attr_size
                        name_idx = struct.unpack_from("<I", raw, a + 4)[0]
                        dtype = raw[a + 15]
                        aname = strings[name_idx] if name_idx < len(strings) else ""
                        if aname == "minSdkVersion" and dtype == 0x10:  # INT
                            min_sdk = struct.unpack_from("<I", raw, a + 16)[0]
            off += size
        return min_sdk
    except Exception:
        return None


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
    # 防呆一：文件名与 flavor 交叉（发布传反是最容易犯的错）
    low = os.path.basename(src).lower()
    if not a.force and (("modern" in low and a.flavor != "modern")
                        or ("legacy" in low and a.flavor != "legacy")):
        sys.exit("文件名含 %s 却要发布到 %s 渠道——疑似拿错安装包。\n"
                 "确认无误可用 --force 强制发布。" % (low, a.flavor))
    # 防呆二：解析 APK 声明的 minSdkVersion，堵住"老平板装上现代包"的最后一道缝
    msdk = apk_min_sdk(src)
    if msdk is not None:
        if a.flavor == "legacy" and msdk >= 21:
            if not a.force:
                sys.exit("该 APK 声明 minSdkVersion=%d，无法在老平板（Android 4.x）上安装——\n"
                         "这是 modern 包，请改用 legacy 安装包发布，或用 --force 强制。" % msdk)
            print("[warn] 强制发布 minSdk=%d 的包到 legacy 渠道：老平板将安装失败！" % msdk)
        if a.flavor == "modern" and msdk < 21:
            print("[warn] 注意：该 APK 声明 minSdkVersion=%d（低于 modern 渠道预期 21）" % msdk)
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
    pp.add_argument("--force", action="store_true", help="跳过 flavor 防呆校验（确认拿对包时才用）")
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
