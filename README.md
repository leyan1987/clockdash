# ClockDash · 时钟看板

把一台老旧安卓平板变成一块**屏幕常亮的信息看板**：大字时钟、农历月历（含法定节假日/调休）、当地一周天气、循环待办与全屏提醒。为 2011 年前后的低配设备设计，**纯原生 View、零第三方框架、无 WebView、无图片资源**，APK 约 250KB，常驻内存 10–20MB，平均 CPU 占用接近 0。

```
┌─────────────────┬──────────────────┬─────────────────┐
│   09:41         │      11月        │  近期待办        │
│   11月1日 星期日  │  一 二 三 四 五 六 日│  今天 09:00      │
│                 │   …  1(今天) …   │  交物业费        │
│   8° 晴转多云    │   … (农历双行) …  │  明天 08:00      │
│   最高14°·最低2°  │   … (休/班标记) … │  吃降压药 · 每天  │
│   [一周预报]     │   • 有待办的日期   │  …              │
└─────────────────┴──────────────────┴─────────────────┘
```

## 订阅制的核心价值

**一个订阅账号 = 一套配置，可以装任意多台设备，全部自动同步同一份内容。**

买一台平板、添一遍事项，家里客厅、卧室、父母家每台设备都自动跟着更新——**不需要逐台添加待办、逐台改设置**。想让不同位置的设备显示不同内容，再开一个账号即可。

- 平板/看板只需填 `账号名:口令`，无需在后台逐台登记设备
- 网页后台改一次，所有设备约 5 分钟内同步到位
- 按年/月续费，到期后设备停同步（时钟/农历/已缓存提醒照常），续费自动恢复

## 功能特性

- **时钟 / 日期**：每秒刷新（仅数字变化时重绘），全屏沉浸
- **农历月历**：每格双行（日期 + 农历/节日），初一显示月名，春节/除夕/中秋等显示节日名；有待办的日期数字变琥珀色并带圆点
- **法定节假日 / 调休**：放假日标红、调休补班日标"班"，左侧日期行追加节日名（数据源 [holiday-cn](https://github.com/NateScarlet/holiday-cn)，随国务院公告年度更新）
- **一周天气**：高德（服务器代理，前 4 天） + Open-Meteo 补齐到 7 天，当前温度 + 高低温 + 湿度风向；无高德 Key 时自动全用 Open-Meteo
- **循环待办**：按小时/天/周/月/年重复，支持间隔、星期多选、生效时段、结束日期；列表只显示每条待办的最近一次，过点自动跳到下一次
- **全屏提醒**：精确闹钟 → 亮屏 + 铃声 + 震动 + 大按钮（知道了 / 10分钟后再提醒），锁屏可弹出，开机自动恢复
- **平板端直接管理待办**：设置页「管理本机待办」可新增/修改/删除（单机版和在线版都能用）
- **手机扫码配置**：平板出示二维码，手机扫码填写服务器地址和口令，全程不用碰平板键盘
- **订阅账号**：管理员在网页后台新建账号、设到期日期、按月/年续费；到期账号列表标红
- **网页后台**：手机浏览器管理待办、搜索城市、推送显示设置、历史待办查询、备份恢复
- **历史待办**：按时间范围/关键词查询已过期的发生记录；删除待办时规则自动归档，历史仍可查
- **夜间模式**：定时把屏幕亮度降到 6%，可选"只显示时钟"
- **离线兜底**：断网时时钟/日历/夜间模式照常工作，数据用最后缓存
- **在线看护**：服务器记录每台设备（含订阅账号的全部设备）最后通讯时间，超过阈值（默认 15 分钟）经企业微信/钉钉/飞书/自定义 Webhook 推送失联告警，恢复时再通知一次；网页后台可看在线状态、告警历史，也可给平板留言（下次轮询带回）。平板端无需改动、不额外耗电
- **OTA 自更新（在线版）**：平板每 5 分钟的轮询顺带发现新版本，自动后台下载（MD5 校验，约 300KB）并通知栏提示"点此安装"；设置页也有"检查更新"手动入口。防篡改由 APK 签名兜底（系统安装器校验，不一致直接拒绝）。服务商一条命令发布新版本

## 在线版 vs 单机版

| | 在线版（订阅） | 单机版（不买服务器） |
|---|---|---|
| 服务器 | 需要（VPS / NAS / 常开电脑） | 不需要 |
| 待办编辑 | 网页后台统一编辑 + 平板本地编辑 | 平板本地编辑 / 手机扫码连平板编辑 |
| 多设备同步 | ✅ 一个账号任意多台，自动同步同一套 | ❌ 各设备独立 |
| 一周天气 | ✅ 服务器代理高德 + Open-Meteo | ✅ 平板填本机高德 Key，或直连 Open-Meteo |
| 在线看护 / 留言 | ✅ | ❌ |
| 订阅到期控制 | ✅ | 不涉及 |
| 备份 | ✅ 网页一键 + 定时 | 数据都在平板本地 |

单机版配置：平板设置页服务器地址留空，填本机高德 Key + 行政区码即可独立运行。

> 面向最终用户的**安装与日常使用手册**（含两种版本的详细区别、一步步图文说明与常见问题）见 [docs/用户手册.md](docs/用户手册.md)。

## 适用硬件

| 项目 | 要求 |
|---|---|
| 系统 | Android 4.0.3+（在 RK2918 / 512MB / 800×480 的 2011 年平板上开发验证） |
| 存储 | APK 约 250KB |
| 网络 | 在线版需能访问服务器（纯 HTTP）；单机版无需网络也能显示时钟日历 |

## 系统架构

```
平板 / 浏览器看板 (Android 4.0.3+ / board.html)
 │  HTTP + 账号口令（老系统无 TLS 1.2，故全链路 HTTP）
 ▼
VPS / NAS / 电脑：server.py（Python 标准库，单文件）
 ├─ /api/health    匿名健康检查（监控探活）
 ├─ /api/login     账号登录（网页后台，签发会话）
 ├─ /api/todos     待办规则（GET 拉取 / POST 保存并归档被删规则）
 ├─ /api/config    显示设置推送（夜间模式/城市/刷新间隔）
 ├─ /api/weather   天气代理：──HTTPS──> 高德(有Key) → Open-Meteo 兜底
 ├─ /api/holidays  节假日代理：──HTTPS──> holiday-cn，24h 缓存
 ├─ /api/check     版本检查（5 分钟；兼作心跳 + 服务器留言捎带）
 ├─ /api/history   历史待办（已过期发生记录 + 被删规则归档）
 ├─ /api/devices   设备库（管理员含订阅账号的虚拟设备）
 ├─ /api/message   管理员给平板留言（下次轮询带回）
 ├─ /api/watchdog  在线看护：状态/配置/告警历史
 ├─ /api/backup    备份与恢复（仅管理员）
 └─ /api/users     订阅账号管理（仅管理员）
                    └─ 失联/恢复 ──HTTPS──> 企业微信/钉钉/飞书/自定义 Webhook
```

服务器在公网、平板在内网 NAT 后，没有固定 IP，服务器无法反向连接平板，因此做不到 TCP 意义上的"真推送"。在线看护用两层组合达到同等效果：失联告警由服务器主动推到手机（Webhook）；发给平板的留言排队等它下一次 `/api/check` 带回（离线消息补发）。

为什么平板直连不了高德/Open-Meteo 的 HTTPS？老安卓只有 TLS 1.0 且证书库停留在 2012 年，现代 HTTPS 全部握手失败——所以由服务器代抓，平板只说 HTTP。天气源无 Key 时服务器自动退回 Open-Meteo，服务器不可用时平板还会再兜底直连 Open-Meteo 的 HTTP 端点（已实测长期可用）。

## 目录结构

```
├── android/            安卓应用工程（minSdk 15，AGP 8.5.2 / Gradle 8.7）
│   └── app/src/main/
│       ├── java/com/clockdash/   18 个类，全部原生 API
│       │   ├── Recur.java        循环日程展开引擎（纯 Java，可单测）
│       │   ├── Lunar.java        农历算法 + 数据表 1900-2049（纯 Java，可单测）
│       │   ├── DashboardActivity 主界面（三栏）
│       │   ├── PairActivity      单机版扫码配置（内置临时局域网服务）
│       │   └── …
│       └── res/                  布局/样式/字符串（中文）
├── server/
│   ├── server.py           后台服务器（纯标准库，Python 3.7+）
│   ├── backup.py           备份恢复工具（纯标准库）
│   ├── app_update.py       安卓端 OTA 发布工具（纯标准库）
│   ├── apps/               OTA 安卓安装包 + manifest.json（发布时生成，不入库）
│   ├── web/index.html      网页后台（vanilla JS）
│   ├── web/board.html      浏览器看板（PWA，可全屏）
│   ├── clockdash.service   systemd 单元
│   ├── clockdash-backup.service / .timer   定时备份
│   └── data/               运行时生成，不入库：口令、用户库、订阅、
│                           按账号隔离的 todos_<key>.json / config_<key>.json
├── docs/
│   └── 用户手册.md         面向普通用户的安装与使用手册（单机版/在线版）
├── tests/
│   ├── LunarTest.java      农历锚点测试（70 项）
│   ├── RecurTest.java      循环引擎测试（31 项）
│   ├── recur_port_test.py  Recur 算法 Python 侧语义验证（26 项）
│   ├── audit.py            静态引用审计
│   └── make_icon.py        应用图标生成器
├── build-apk.cmd       Windows 一键打包（镜像到 ASCII 路径构建）
└── dist/               构建产物：ClockDash-legacy.apk / ClockDash-modern.apk
```

## 快速开始（在线版）

### 1. 部署服务器

服务器程序放在 `/home/server`（可改成别的路径，但 systemd 单元里的路径要同步改）：

```bash
cd /home/server
python3 server.py 8765
# 首次启动自动生成口令 data/server-token.txt，
# 并在终端打印一次设置页地址与初始口令（请记牢）
```

常驻运行：

```bash
sudo cp clockdash.service clockdash-backup.service clockdash-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now clockdash
sudo systemctl enable --now clockdash-backup.timer
```

云服务器记得在安全组放行 TCP 8765，并先做一次手动备份再改配置。

### 2. 平板安装 APK

设置 → 应用 → 勾选"未知来源"，然后安装 `dist/ClockDash-legacy.apk`（老平板）或 `ClockDash-modern.apk`（现代设备），可用 `adb install -r` 或拷到存储卡用文件管理器安装。

### 3. 管理员登录后台

浏览器打开 `http://服务器IP:8765/`，用 `admin` + 初始口令（`data/server-token.txt`）登录。

### 4. 建订阅账号

后台「订阅账号」→ 填账号名、口令、到期日期 → 新建。需要延长时点该账号的「＋1年」，或在下方输入月数点「续费」。

### 5. 平板配置

平板设置页服务器地址填 `http://服务器IP:8765`，口令填 `账号名:口令`（或只填口令）。保存后即自动同步该账号的待办与设置。多台设备填同一个账号口令即可全部同步。

之后平板完全不用碰——后台保存约 5 分钟内推送到位，无修改时每小时完整同步一次。

### 6. 浏览器看板（可选）

访问 `http://服务器IP:8765/board.html`，输入同一个 `账号名:口令` 即可把任意浏览器/电视盒子变成第二块看板；支持 PWA 全屏，与平板同步同一套内容。

### 单机版

不部署服务器：平板设置页服务器地址留空，填本机高德 Key + 行政区码；待办在「管理本机待办」或手机扫码连平板本地服务编辑。

## 备份与恢复

`server/backup.py`（纯标准库）整目录打包 `data/`——含口令、用户库、订阅、待办、配置与看护状态。备份文件权限 0600，内含凭据，请妥善保管并另存异地。

```bash
# 手动备份（默认保留最近 30 份，pre-restore-* 永不自动清理）
python3 backup.py create --data ./data --out ./backups
python3 backup.py list --out ./backups

# 恢复：先只校验，确认后再覆盖
python3 backup.py restore backups/clockdash-YYYYMMDD-HHMMSS-XXX.tar.gz \
  --data ./data --out ./backups --dry-run
python3 backup.py restore backups/clockdash-YYYYMMDD-HHMMSS-XXX.tar.gz \
  --data ./data --out ./backups --force
```

恢复前校验 manifest 里每个文件的 SHA256，拒绝绝对路径 / `..` 穿越 / 非普通文件，校验通过才逐文件替换；已有同名文件必须加 `--force`。恢复前会自动把当前数据另存为 `pre-restore-*`。

**网页后台**：管理员可一键备份、查看列表、点选恢复；恢复需两次确认（确认框 + 手动输入 `RESTORE`）。非管理员看不到该区块，调用备份 API 一律 403。

**定时备份**：systemd timer 默认每天 03:17 备份一次（随机延迟），保留 30 份；`Persistent=true` 保证停机期间错过的备份开机后补做。

## 安卓端 OTA 发布

出新版本后，把两个 APK 上传到服务器任意目录，然后：

```bash
python3 app_update.py publish legacy ClockDash-legacy-44.apk --name 1.44 --notes "修复xxx"
python3 app_update.py publish modern ClockDash-modern-44.apk --name 1.44 --notes "修复xxx"
python3 app_update.py list          # 查看当前发布状态
python3 app_update.py clear legacy  # 撤销某版本的更新提示
```

- 文件名以 `-<versionCode>.apk` 结尾时可省略 `--code`（推荐命名习惯）
- APK 存入 `server/apps/`，manifest 记录版本号/MD5/说明；**发布后无需重启服务器**
- 客户端在下一次 5 分钟轮询（`/api/check` 捎带）即收到新版本信息，自动后台下载
  （MD5 校验防下载损坏）并通知"点此安装"；也可在平板设置页"检查更新"手动触发
- 安装链路：legacy 走系统安装器 Intent；modern（Android 5.0+）走框架 PackageInstaller，
  Android 8+ 首次需授予"安装未知应用"。防篡改最终由 APK 签名保证——
  签名与已装应用不一致时系统拒绝安装，MD5 只负责检出下载损坏
- 服务器端对应路由：`GET /api/app/check?flavor=&code=`（鉴权）与
  `GET /apps/<文件>.apk`（公开下载，限流内）

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

- `remind`：提前提醒分钟数（0=准时，-1=不提醒，1440=提前 1 天）
- `repeat`：`none / hourly / daily / weekly / monthly / yearly`
- `weekdays`：仅 weekly，1=周一…7=周日；缺省按开始日期的星期
- `window_from/to`：仅 hourly，每天生效时段（默认 08:00–22:00，避免半夜响铃）
- `end`：可选结束日期；monthly/yearly 遇月份无此日（如 2 月 30）自动跳过

一般不用手写 JSON，网页后台或平板设置页编辑即可。

## 从源码构建

依赖：JDK 17、Android SDK（platform 34 / build-tools 34）、Gradle 8.7。

- **Windows 一键**：`build-apk.cmd` —— 因 AGP/aapt 不支持中文项目路径，脚本把工程镜像到纯 ASCII 路径构建再把两个 APK 拷回 `dist\`
- **Android Studio**：Open 打开 `android\` → 选 flavor → Build APK(s)
- **命令行**：`cd android && gradle assembleLegacyRelease assembleModernRelease`

### 双版本说明

| 版本 | 适用 | minSdk | targetSdk | 产物 |
|---|---|---|---|---|
| **legacy** | 老平板 / 电视 / 机顶盒 | 15（Android 4.0.3+） | 15 | `ClockDash-legacy.apk` |
| **modern** | 64 位现代手机 / 平板 / 新电视盒 | 21（Android 5.0+） | 31 | `ClockDash-modern.apk` |

modern 版含现代系统适配：通知渠道（8.0+）、PendingIntent 不可变标志（12.0+）、精确闹钟被收回时退化为近似闹钟、提醒走全屏意图通知、开机自启走全屏通知。注意：充电常亮所需的"修改系统设置"权限需手动授予；存储卡导入配置在分区存储设备上不可用（用扫码/手动输入代替）。两版功能一致，服务器无需改动。覆盖升级时签名必须一致，否则安装失败。

### 测试

| 测试 | 覆盖 | 运行方式 | 状态 |
|---|---|---|---|
| `tests/LunarTest.java` | 农历数据表+算法：2000–2031 春节逐年、中秋/端午/除夕/闰月、日名 1–30 全覆盖 | `javac -encoding UTF-8 -d out android/.../Lunar.java tests/LunarTest.java && java -cp out com.clockdash.LunarTest` | ✅ 70/70 |
| `tests/RecurTest.java` | 循环引擎：六种重复、间隔、星期、月末跳过、闰年、结束日期、上限 | `javac -encoding UTF-8 -d out android/.../{Recur,Todo}.java tests/RecurTest.java && java -cp out RecurTest` | ✅ 31/31 |
| `tests/recur_port_test.py` | 服务端 Recur 移植与安卓端语义一致性 | `python tests/recur_port_test.py` | ✅ 26/26 |
| `tests/audit.py` | 静态审计：Java↔资源↔Manifest 交叉引用 | `python tests/audit.py` | ✅ 无缺失 |

## 安全与运维加固

服务器因老安卓限制走明文 HTTP，但已做多层防护：

- **鉴权**：设备/账号口令与网页会话双通道；口令与会话均哈希落盘（PBKDF2 60 万次 / SHA256 摘要），旧格式首次使用静默升级。请求鉴权按 SHA256 摘要索引定位候选后再做一次 PBKDF2 确认（实测 10 设备 + 10 账号规模下每个请求 ~170ms，与规模无关），未命中请求零 PBKDF2，账号规模化不拖垮服务
- **限流**：登录失败按账号+IP 双维度锁定；接口按 IP 120 次/分钟限流，异常请求 429
- **会话**：30 天绝对有效期 + 7 天空闲超时 + 每账号最多 5 个并发会话
- **审计日志**：时间戳 + IP + 账号 + 方法 + 路径 + 状态码，不记录 URL 中的口令
- **安全响应头**：X-Content-Type-Options / X-Frame-Options / Referrer-Policy / CSP（放行看板与后台直连的天气/城市 API 域）
- **可靠性**：线程上限 64 + 15 秒 socket 超时（防慢连接攻击），端口占用明确报错，设备库损坏自动降级
- **备份**：网页一键备份/恢复 + systemd 定时器，恢复前自动留底、逐文件校验和
- **systemd 加固**：ProtectSystem/ProtectHome/PrivateTmp，仅放行 data 与 backups 两个可写目录
- **探活**：`GET /api/health` 匿名返回 `{"ok":true}`，供监控/负载均衡使用

## 设计取舍

- **纯 HTTP + 口令**而不是 HTTPS：老安卓 TLS 能力太弱（TLS 1.0-only + 旧证书库），自建 CA/钉证书在现代 OpenSSL 上配置 TLSv1 又极其麻烦。口令与数据本身不敏感，配合上述限流/审计/权限隔离足够家用与小规模部署；VPS 端口不要与敏感服务复用。
- **关闭硬件加速**（`hardwareAccelerated=false`）：ICS 时代 Vivante GC800 的 HW 驱动以不稳定著称，静态文本界面软件渲染毫无压力且更稳。
- **农历离线算法**而非接口：日历是常显核心，不能依赖网络；数据表经 40 项真实锚点校验。
- **每条待办只显示最近一次**：短周期循环（每 2 小时喝水）不会把列表顶满。
- **账号即配置集**：靠服务端把账号口令解析成固定隔离键，同账号所有设备落在同一份数据文件上——不改一处路径逻辑就实现了多设备共享，也避免了逐台登记设备的维护成本。
- **单 Activity + Handler**：没有 Service 常驻，同步靠 AlarmManager 唤醒，提醒靠精确闹钟 + 广播；另有每 5 分钟的轻量推送检查（只拉数据版本号，几十字节），后台一保存最迟约 5 分钟内触发完整同步。

## 常见问题

- **桌面上没有图标**：需 v1.0+ 版本（旧版误用系统框架图标导致部分桌面不显示）；覆盖安装即可。
- **离线时显示什么**：时钟/日期/月历/夜间模式/常亮全部照常，待办区显示"离线模式 · 仅时钟日历"，天气显示占位符；联网后最迟约 5 分钟自动恢复。
- **提醒不响**：检查"提醒时播放铃声并震动"是否开启、媒体音量、平板系统时间是否准确（建议开启"自动确定日期和时间"）。
- **天气一直等待数据**：未配高德 Key 时走 Open-Meteo，需要设置里有经纬度（后台搜索城市推送，或单机版本机填 Key + 行政区码）。
- **电池鼓包风险**：老锂电 24 小时插电一年大概率鼓包，建议拆掉电池直接用电源供电（RK2918 平板大多支持无电池开机）。
- **设置入口**：右上角连点 3 次 / 长按时钟 / 菜单键——为触摸失灵的屏幕留的保底。
- **网页后台怎么登录**：管理员 `admin` + 初始口令（`data/server-token.txt`）。订阅账号由管理员在「订阅账号」区块创建，客户用 `账号名:口令` 在平板/看板登录；一个账号的设备共享同一套配置。
- **订阅到期了怎么办**：联系服务商续费；到期期间时钟/农历/已缓存提醒照常，续费后设备下次同步自动恢复，无需重配。
- **服务起不来 / 端口被占**：启动时会在日志明确报"端口无法绑定"；systemd 下用 `journalctl -u clockdash -n 50` 查看。若改了目录，记得同步改 `/etc/systemd/system/clockdash.service` 里的路径。

## License

[MIT](LICENSE)
