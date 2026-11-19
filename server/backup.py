#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ClockDash 数据备份与恢复（纯标准库）。

用法：
  python backup.py create [--data DIR] [--out DIR] [--keep N]
  python backup.py list   [--out DIR]
  python backup.py restore <备份文件> [--data DIR] [--force] [--dry-run]

设计要点：
  - 备份包含 data/ 下全部文件（口令、用户库、设备令牌哈希、待办、配置、
    看护状态），单个 tar.gz + manifest.json（逐文件 SHA256）。
  - 备份文件权限 0600：里面含凭据，不能让本机其他用户读。
  - 恢复前自动把当前数据再备份一份（pre-restore-*），误操作可回退。
  - 恢复先解到临时目录、逐文件校验 SHA256，通过后才替换，避免半途写坏。
  - 拒绝压缩包里的绝对路径、.. 路径穿越、符号链接/设备文件。
"""

import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
from datetime import datetime

BASE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DATA = os.path.join(BASE, "data")
DEFAULT_OUT = os.path.join(BASE, "backups")
MANIFEST = "manifest.json"
KEEP_DEFAULT = 30
PREFIX = "clockdash-"
PRE_RESTORE_PREFIX = "pre-restore-"


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def _stamp():
    # 带微秒：恢复前的自动备份可能与原备份发生在同一秒，撞名会导致
    # Windows 上 os.replace 覆盖失败（实测踩到）
    return datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]


def _secure_mode(path):
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass   # Windows 上 chmod 语义不同，忽略


def _data_files(data_dir):
    """返回 data 目录下所有普通文件的相对路径（不递归子目录，当前数据均为平铺）"""
    if not os.path.isdir(data_dir):
        raise RuntimeError("数据目录不存在: %s" % data_dir)
    out = []
    for name in sorted(os.listdir(data_dir)):
        p = os.path.join(data_dir, name)
        if os.path.isfile(p) and name != MANIFEST:
            out.append(name)
    return out


def create(data_dir, out_dir, keep):
    names = _data_files(data_dir)
    if not names:
        raise RuntimeError("数据目录为空，拒绝生成空备份: %s" % data_dir)
    os.makedirs(out_dir, exist_ok=True)

    manifest = {
        "app": "ClockDash",
        "format": 1,
        "created": datetime.now().isoformat(timespec="seconds"),
        "files": {n: {"size": os.path.getsize(os.path.join(data_dir, n)),
                      "sha256": _sha256(os.path.join(data_dir, n))} for n in names},
    }
    dest = os.path.join(out_dir, "%s%s.tar.gz" % (PREFIX, _stamp()))
    # 先写临时文件再改名，避免中断留下半个压缩包
    tmp = dest + ".tmp"
    try:
        with tarfile.open(tmp, "w:gz") as tf:
            mbytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
            info = tarfile.TarInfo(MANIFEST)
            info.size = len(mbytes)
            info.mtime = int(datetime.now().timestamp())
            tf.addfile(info, io.BytesIO(mbytes))
            for n in names:
                tf.add(os.path.join(data_dir, n), arcname=n)
        _secure_mode(tmp)
        os.replace(tmp, dest)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)

    _prune(out_dir, keep)
    print("备份完成: %s" % dest)
    print("  包含 %d 个文件，%d 字节" % (len(names), os.path.getsize(dest)))
    return dest


def _prune(out_dir, keep):
    """只清理本工具生成的常规备份，pre-restore 备份永不自动删除"""
    if keep <= 0:
        return
    items = []
    for n in os.listdir(out_dir):
        if n.startswith(PREFIX) and n.endswith(".tar.gz"):
            p = os.path.join(out_dir, n)
            if os.path.isfile(p):
                items.append((os.path.getmtime(p), p))
    items.sort(reverse=True)
    for _, p in items[keep:]:
        os.remove(p)
        print("  清理旧备份: %s" % os.path.basename(p))


def list_backups(out_dir):
    if not os.path.isdir(out_dir):
        print("暂无备份目录: %s" % out_dir)
        return
    items = []
    for n in sorted(os.listdir(out_dir)):
        if not n.endswith(".tar.gz"):
            continue
        p = os.path.join(out_dir, n)
        if os.path.isfile(p):
            items.append((n, os.path.getsize(p)))
    if not items:
        print("暂无备份")
        return
    print("备份目录: %s" % out_dir)
    for n, size in items:
        print("  %-42s %8d 字节" % (n, size))


def _safe_members(tf):
    """校验压缩包成员，拒绝路径穿越/绝对路径/特殊文件"""
    members = tf.getmembers()
    for m in members:
        name = m.name.replace("\\", "/")
        if name.startswith("/") or ".." in name.split("/") or name.startswith("./"):
            raise RuntimeError("备份包含不安全路径，拒绝恢复: %s" % m.name)
        if not (m.isfile() or m.isdir()):
            raise RuntimeError("备份包含非普通文件，拒绝恢复: %s" % m.name)
    return members


def restore(backup_path, data_dir, out_dir, force, dry_run):
    if not os.path.isfile(backup_path):
        raise RuntimeError("备份文件不存在: %s" % backup_path)
    with tarfile.open(backup_path, "r:gz") as tf:
        members = _safe_members(tf)
        m = next((x for x in members if x.name == MANIFEST), None)
        if m is None:
            raise RuntimeError("备份缺少 manifest.json，拒绝恢复")
        f = tf.extractfile(m)
        if f is None:
            raise RuntimeError("manifest 无法读取，拒绝恢复")
        manifest = json.loads(f.read().decode("utf-8"))
        if manifest.get("app") != "ClockDash" or manifest.get("format") != 1:
            raise RuntimeError("不是 ClockDash 备份格式，拒绝恢复")
        expected = manifest.get("files") or {}
        if not expected:
            raise RuntimeError("manifest 没有文件清单，拒绝恢复")

        tmpdir = tempfile.mkdtemp(prefix="clockdash-restore-")
        try:
            for name, meta in expected.items():
                if name.replace("\\", "/") != name or ".." in name or name.startswith("/"):
                    raise RuntimeError("manifest 文件名不安全: %s" % name)
                src = next((x for x in members if x.name == name), None)
                if src is None:
                    raise RuntimeError("备份缺少文件: %s" % name)
                fh = tf.extractfile(src)
                if fh is None:
                    raise RuntimeError("无法读取备份文件: %s" % name)
                content = fh.read()
                if len(content) != meta.get("size"):
                    raise RuntimeError("文件长度不符: %s" % name)
                if hashlib.sha256(content).hexdigest() != meta.get("sha256"):
                    raise RuntimeError("文件校验和不符（备份可能损坏）: %s" % name)
                with open(os.path.join(tmpdir, name), "wb") as out:
                    out.write(content)

            if dry_run:
                print("[dry-run] 校验通过，共 %d 个文件，未做任何改动" % len(expected))
                for n in sorted(expected):
                    print("  %s  %d 字节" % (n, expected[n]["size"]))
                return

            if not os.path.isdir(data_dir):
                os.makedirs(data_dir, exist_ok=True)
            existing = [n for n in expected if os.path.exists(os.path.join(data_dir, n))]
            if existing and not force:
                raise RuntimeError(
                    "目标目录已有 %d 个同名文件，拒绝覆盖。确认后加 --force：%s"
                    % (len(existing), ", ".join(sorted(existing)[:5])))

            # 恢复前自动备份当前数据，文件名加 pre-restore- 前缀，永不自动清理
            if existing:
                saved = create(data_dir, out_dir, 0)
                pre = os.path.join(out_dir,
                                   PRE_RESTORE_PREFIX + os.path.basename(saved))
                os.replace(saved, pre)
            # 逐文件原子替换
            for n in expected:
                os.replace(os.path.join(tmpdir, n), os.path.join(data_dir, n))
            print("恢复完成: %d 个文件 -> %s" % (len(expected), data_dir))
            if existing:
                print("  恢复前的原数据已另存为 pre-restore-* 备份")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description="ClockDash 数据备份与恢复")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("create", help="创建备份")
    p.add_argument("--data", default=DEFAULT_DATA)
    p.add_argument("--out", default=DEFAULT_OUT)
    p.add_argument("--keep", type=int, default=KEEP_DEFAULT, help="保留最近 N 份（0=不清理）")

    p = sub.add_parser("list", help="列出备份")
    p.add_argument("--out", default=DEFAULT_OUT)

    p = sub.add_parser("restore", help="从备份恢复")
    p.add_argument("backup")
    p.add_argument("--data", default=DEFAULT_DATA)
    p.add_argument("--out", default=DEFAULT_OUT)
    p.add_argument("--force", action="store_true", help="覆盖已有文件")
    p.add_argument("--dry-run", action="store_true", help="只校验不改动")

    args = ap.parse_args()
    try:
        if args.cmd == "create":
            create(args.data, args.out, args.keep)
        elif args.cmd == "list":
            list_backups(args.out)
        else:
            restore(args.backup, args.data, args.out, args.force, args.dry_run)
    except Exception as e:
        print("错误: %s" % e, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
