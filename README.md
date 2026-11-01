# ClockDash · 时钟看板

把一台老旧安卓平板变成一块**屏幕常亮的信息看板**：大字时钟、农历月历（含法定节假日/调休）、当地天气、服务器同步的循环待办与全屏提醒。为 2011 年前后的低配设备设计，**纯原生 View、零第三方框架、无 WebView、无图片资源**，APK 仅约 250KB，常驻内存 10–20MB，平均 CPU 占用接近 0。

```
┌─────────────────┬──────────────────┬─────────────────┐
│   09:41         │      11月        │  近期待办        │
│   11月1日 星期日  │  一 二 三 四 五 六 日│  今天 09:00      │
│                 │   …  1(今天) …   │  交物业费        │
│   8° 晴转多云    │   … (农历双行) …  │  明天 08:00      │
│   最高14°·最低2°  │   … (休/班标记) … │  吃降压药 · 每天  │
│   [明日天气预报]  │   • 有待办的日期   │  …              │
└─────────────────┴──────────────────┴─────────────────┘
```

## 功能特性

- **时钟 / 日期**：每秒刷新（仅数字变化时重绘），全屏沉浸
- **农历月历**：每格双行（日期 + 农历/节日），初一显示月名，春节/除夕/中秋等显示节日名；有待办的日期数字变琥珀色并带圆点
- **法定节假日 / 调休**：放假日标红、调休补班日标"班"，左侧日期行追加节日名（数据源 [holiday-cn](https://github.com/NateScarlet/holiday-cn)，随国务院公告年度更新）
- **天气**：高德 API（服务器代理）或 Open-Meteo 兜底，当前温度 + 今日高低温 + 三日预报 + 湿度风向
- **循环待办**：按小时/天/周/月/年重复，支持间隔、星期多选、生效时段、结束日期；列表只显示每条待办的最近一次，过点自动跳到下一次
- **全屏提醒**：精确闹钟 → 亮屏 + 铃声 + 震动 + 大按钮（知道了 / 10分钟后再提醒），锁屏可弹出，开机自动恢复
- **手机扫码配置**：平板出示二维码，手机扫码即可填写服务器地址等基本设置，全程不用碰平板键盘
- **网页后台**：手机浏览器管理待办、搜索城市、推送显示设置
- **夜间模式**：定时把屏幕亮度降到 6%，可选"只显示时钟"
- **离线兜底**：断网时时钟/日历/夜间模式照常工作，数据用最后缓存

## 适用硬件

| 项目 | 要求 |
|---|---|
| 系统 | Android 4.0.3+（在 RK2918 / 512MB / 800×480 的 2011 年平板上开发验证） |
| 存储 | APK 约 250KB |
| 网络 | 与服务器同网或可访问 VPS（纯 HTTP） |

## 系统架构

```
平板 (Android 4.0.3)
 │  HTTP + 口令（老系统无 TLS 1.2，故全链路 HTTP）
 ▼
VPS / NAS / 电脑：server.py（Python 标准库，单文件）
 ├─ /api/todos     待办规则（网页后台编辑保存）
 ├─ /api/config    显示设置推送（夜间模式/城市/刷新间隔）
 ├─ /api/weather   天气代理：──HTTPS──> 高德(有Key) → Open-Meteo 兜底，10min缓存
 └─ /api/holidays  节假日代理：──HTTPS──> holiday-cn，24h缓存
```

为什么平板直连不了高德/Open-Meteo 的 HTTPS？老安卓只有 TLS 1.0 且证书库停留在 2012 年，现代 HTTPS 全部握手失败——所以由服务器代抓，平板只说 HTTP。天气源无 Key 时服务器自动退回 Open-Meteo，服务器不可用时平板还会再兜底直连 Open-Meteo 的 HTTP 端点（已实测长期可用）。

## 目录结构

```
├── android/            安卓应用工程（minSdk 15，AGP 8.5.2 / Gradle 8.7）
│   └── app/src/main/
│       ├── java/com/clockdash/   15 个类，全部原生 API
│       │   ├── Recur.java        循环日程展开引擎（纯 Java，可单测）
│       │   ├── Lunar.java        农历算法 + 数据表 1900-2049（纯 Java，可单测）
│       │   ├── DashboardActivity 主界面（方案A三栏）
│       │   ├── PairActivity      手机扫码配置（内置临时局域网配置服务）
│       │   └── …
│       └── res/                  布局/样式/字符串（中文）
├── server/
│   ├── server.py       后台服务器（纯标准库，Python 3.7+）
│   ├── web/index.html  手机网页后台（无框架 vanilla JS）
│   ├── clockdash.service  systemd 单元示例
│   └── data/           运行时生成（口令/待办/配置），不入库
├── tests/
│   ├── LunarTest.java  农历锚点测试（40 项）
│   ├── RecurTest.java  循环引擎测试（26 项）
│   ├── audit.py        静态引用审计（资源/类/常量交叉核对）
│   └── make_icon.py    应用图标生成器
├── build-apk.cmd       Windows 一键打包（镜像到 ASCII 路径构建）
└── dist/               构建产物输出（不入库）
```

## 快速开始

### 1. 启动服务器（VPS / NAS / 常开电脑）

```bash
python3 server.py 8765
# 首次启动自动生成口令 data/server-token.txt，
# 并打印带口令的设置页地址： http://<IP>:8765/?token=XXXXXXXX
```

VPS 常驻见 [server/clockdash.service](server/clockdash.service)；华为云/阿里云记得在安全组放行 TCP 8765。

### 2. 平板安装 APK

设置 → 应用 → 勾选"未知来源"，然后：

```bash
adb install -r dist/ClockDash-v1.0.apk
# 或把 APK 拷到存储卡用文件管理器安装
```

### 3. 手机扫码配置

平板桌面打开时钟看板 → 右上角连点 3 次（或长按时钟）进入设置 → **「手机扫码配置」** → 手机扫平板屏幕上的二维码 → 在手机网页里填服务器地址和口令 → 保存，平板立即生效（需同一 WiFi，配对码防误改）。

### 4. 日常使用

手机扫「显示设置页二维码」打开网页后台：添加循环待办、填高德 Key、搜索城市推送。之后**平板完全不用碰**，每 10 分钟自动同步。

## 待办数据格式（todos.json）

```json
{
  "version": 1,
  "todos": [
    {"id": "t1", "title": "交物业费",     "date": "2026-11-01", "time": "09:00", "remind": 30,   "repeat": "none"},
    {"id": "t2", "title": "吃降压药",     "date": "2026-11-02", "time": "08:00", "remind": 10,   "repeat": "daily",   "interval": 1},
    {"id": "t3", "title": "给妈妈打电话", "date": "2026-11-03", "time": "19:00", "remind": 15,   "repeat": "weekly",  "interval": 1, "weekdays": [2, 5]},
    {"id": "t4", "title": "喝水",         "date": "2026-11-01", "time": "09:00", "remind": 0,    "repeat": "hourly",  "interval": 2, "window_from": "09:00", "window_to": "21:00"},
    {"id": "t5", "title": "缴水电气费",   "date": "2026-11-05", "time": "09:00", "remind": 60,   "repeat": "monthly", "interval": 1},
    {"id": "t6", "title": "年度体检复查", "date": "2027-03-15", "time": "08:30", "remind": 1440, "repeat": "yearly",  "interval": 1}
  ]
}
```

- `remind`：提前提醒分钟数（0=准时，-1=不提醒，1440=提前1天）
- `repeat`：`none / hourly / daily / weekly / monthly / yearly`
- `weekdays`：仅 weekly，1=周一…7=周日；缺省按开始日期的星期
- `window_from/to`：仅 hourly，每天生效时段（默认 08:00–22:00，避免半夜响铃）
- `end`：可选结束日期；monthly/yearly 遇月份无此日（如 2 月 30）自动跳过

一般不用手写 JSON，网页后台编辑即可。

## 从源码构建

依赖：JDK 17、Android SDK（platform 34 / build-tools 34）、Gradle 8.7。

- **Windows 一键**：`build-apk.cmd` —— 因 AGP/aapt 不支持中文项目路径，脚本会把工程镜像到纯 ASCII 路径构建再把 APK 拷回 `dist\`（工具链布局可在脚本顶部改）
- **Android Studio**：Open 打开 `android\` 目录 → Build APK(s)
- **命令行**：`cd android && gradle assembleRelease`（Gradle 已配阿里云镜像，国内构建顺畅；release 包默认调试签名可直接安装）

### 测试

| 测试 | 覆盖 | 运行方式 | 状态 |
|---|---|---|---|
| `tests/LunarTest.java` | 农历数据表+算法：2000–2031 年春节逐年、中秋/端午/除夕/闰月 | `javac -encoding UTF-8 -d out android/.../Lunar.java tests/LunarTest.java && java -cp out com.clockdash.LunarTest` | ✅ 40/40 |
| `tests/RecurTest.java` | 循环引擎：六种重复、间隔、星期、月末跳过、闰年、结束日期、上限 | `javac -encoding UTF-8 -d out android/.../{Recur,Todo}.java tests/RecurTest.java && java -cp out RecurTest` | ✅ 26/26 |
| `tests/audit.py` | 静态审计：Java↔资源↔Manifest 交叉引用、Intent extras 对称 | `python tests/audit.py` | ✅ 无缺失 |

## 设计取舍

- **纯 HTTP + 口令**而不是 HTTPS：老安卓 TLS 能力太弱（TLS 1.0-only + 旧证书库），自建 CA/钉证书在现代 OpenSSL 上配置 TLSv1 又极其麻烦。待办数据不敏感，口令 + 不放行多余权限足够家用；VPS 端口不要与敏感服务复用。
- **关闭硬件加速**（`hardwareAccelerated=false`）：ICS 时代 Vivante GC800 的 HW 驱动以不稳定著称，静态文本界面软件渲染毫无压力且更稳。
- **农历离线算法**而非接口：日历是常显核心，不能依赖网络；数据表经 40 项真实锚点校验。
- **每条待办只显示最近一次**：短周期循环（每 2 小时喝水）不会把列表顶满。
- **单 Activity + Handler**：没有 Service 常驻，同步靠 AlarmManager 唤醒，提醒靠精确闹钟 + 广播。

## 常见问题

- **桌面上没有图标**：需 v1.0+ 版本（旧版误用了系统框架图标导致部分桌面不显示）；`adb install -r` 覆盖升级即可。
- **离线时显示什么**：时钟/日期/月历/夜间模式/常亮全部照常，待办区显示"离线模式 · 仅时钟日历"，天气显示占位符；联网后 10 分钟内自动恢复。
- **提醒不响**：检查"提醒时播放铃声并震动"是否开启、媒体音量、以及平板系统时间是否准确（建议开启"自动确定日期和时间"）。
- **天气一直等待数据**：未配置高德 Key 时走 Open-Meteo，需要平板设置里有经纬度（网页后台搜索城市推送）。
- **电池鼓包风险**：老锂电 24 小时插电一年大概率鼓包，建议拆掉电池直接用电源供电（RK2918 平板大多支持无电池开机）。
- **设置入口**：右上角连点 3 次 / 长按时钟 / 菜单键——为触摸失灵的屏幕留的保底。

## License

[MIT](LICENSE)
