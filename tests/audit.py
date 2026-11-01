# -*- coding: utf-8 -*-
"""静态引用审计：Java <-> res <-> Manifest 交叉引用检查（一次性工具）"""
import re, os, glob, sys

ROOT = r"F:\日历\android\app\src\main"
problems = []

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

java_files = glob.glob(ROOT + "/java/**/*.java", recursive=True)
java_all = ""
for p in java_files:
    java_all += read(p) + "\n//@file %s\n" % p

layout_files = glob.glob(ROOT + "/res/layout/*.xml")
lay_all = ""
ids_defined = set()
for p in layout_files:
    t = read(p)
    lay_all += t
    ids_defined |= set(re.findall(r'@\+id/(\w+)', t))

strings = set(re.findall(r'<string name="(\w+)"', read(ROOT + "/res/values/strings.xml")))
colors = set(re.findall(r'<color name="(\w+)"', read(ROOT + "/res/values/colors.xml")))
styles = set(re.findall(r'<style name="(\w+)"', read(ROOT + "/res/values/styles.xml")))
mani = read(ROOT + "/AndroidManifest.xml")

# 1) Java 里引用的 R.* 必须存在
for kind, name in sorted(set(re.findall(r'R\.(id|layout|string|color|style)\.(\w+)', java_all))):
    if kind == "id" and name not in ids_defined:
        problems.append("Java 引用了不存在的 id: R.id.%s" % name)
    if kind == "layout" and not os.path.exists(ROOT + "/res/layout/%s.xml" % name):
        problems.append("Java 引用了不存在的 layout: %s" % name)
    if kind == "string" and name not in strings:
        problems.append("Java 引用了不存在的 string: %s" % name)
    if kind == "color" and name not in colors:
        problems.append("Java 引用了不存在的 color: %s" % name)
    if kind == "style" and name not in styles:
        problems.append("Java 引用了不存在的 style: %s" % name)

# 2) 布局里引用的 @style/@string/@color 必须存在
for name in sorted(set(re.findall(r'@style/(\w+)', lay_all))):
    if name not in styles:
        problems.append("布局引用了不存在的 style: @style/%s" % name)
for name in sorted(set(re.findall(r'@string/(\w+)', lay_all + mani))):
    if name not in strings:
        problems.append("布局/清单引用了不存在的 string: %s" % name)
for name in sorted(set(re.findall(r'@color/(\w+)', lay_all))):
    if name not in colors:
        problems.append("布局引用了不存在的 color: @color/%s" % name)

# 3) Manifest 声明的组件类必须存在
for cls in sorted(set(re.findall(r'android:name="\.(\w+)"', mani))):
    if not os.path.exists(ROOT + "/java/com/clockdash/%s.java" % cls):
        problems.append("Manifest 声明的类不存在: %s" % cls)

# 4) Prefs 常量 / AlarmScheduler action 交叉引用
prefs_fields = set(re.findall(r'public static final String (K_\w+)', java_all))
for name in sorted(set(re.findall(r'Prefs\.(K_\w+)', java_all))):
    if name not in prefs_fields:
        problems.append("引用了不存在的常量 Prefs.%s" % name)
actions = set(re.findall(r'public static final String (ACTION_\w+)', java_all))
for name in sorted(set(re.findall(r'AlarmScheduler\.(ACTION_\w+)', java_all))):
    if name not in actions:
        problems.append("引用了不存在的 action AlarmScheduler.%s" % name)

# 5) Intent extra 发送/接收对称性（粗查）
put_keys = set(re.findall(r'putExtra\("([\w-]+)"', java_all))
get_keys = set()
for a, b, c in re.findall(r'getStringExtra\("([\w-]+)"\)|getBooleanExtra\("([\w-]+)"|getLongExtra\("([\w-]+)"', java_all):
    get_keys.add(a or b or c)
for k in sorted(get_keys - put_keys):
    problems.append("读取了从未写入的 extra: %s" % k)

# 6) 同包类互相调用的方法是否存在（按类名粗查）
cls_methods = {}
for p in java_files:
    src = read(p)
    cname = os.path.basename(p)[:-5]
    cls_methods[cname] = set(re.findall(r'(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\]]+\s+(\w+)\s*\(', src))
calls = {
    "Store": ["parseTodos", "fetchTodos", "loadTodosCache", "fetchWeather", "loadWeatherCache",
              "fetchConfigPush", "todosCacheTime", "weatherUrl", "serverBase", "readPrivate",
              "writePrivate", "readSdcardConfig"],
    "Prefs": ["get", "put", "getBool", "putBool", "getInt", "isNotified", "addNotified", "applyStayOn"],
    "AlarmScheduler": ["schedulePeriodic", "scheduleReminders", "snooze", "reqCode"],
    "Recur": ["expand"],
    "HttpUtil": ["get"],
    "QrCodec": ["encode", "decodeNv21"],
    "WeatherInfo": ["parseServer", "parseOpenMeteo", "codeText"],
}
for cls, methods in calls.items():
    for m in methods:
        if m not in cls_methods.get(cls, set()):
            problems.append("%s 缺少被调用的方法: %s()" % (cls, m))

print("检查的 Java 文件数:", len(java_files), " 布局数:", len(layout_files))
print("定义: id=%d string=%d color=%d style=%d" % (len(ids_defined), len(strings), len(colors), len(styles)))
if problems:
    print("\n发现问题 %d 个:" % len(problems))
    for p in problems:
        print("  [X]", p)
    sys.exit(1)
print("\n静态引用审计通过，无缺失引用。")
