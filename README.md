# 腕上书 · 安卓手表电子书阅读器

> **抬起手腕就能接着读。** 一个为小屏幕重新设计的离线阅读器 —— 纯原生 Compose 绘制，不依赖 WebView，**不申请网络权限**。

手表上没有浏览器内核，网页版阅读器在手表上跑不起来。所以这个版本用 **Kotlin + Jetpack Compose（Material 3）原生重写**：排版和渲染全部交给系统绘制，几百 KB 的体积，装得上、跑得动。

---

## 亮点

| | |
|---|---|
| 📖 **四种格式** | TXT、EPUB（含正文插图）、MOBI / AZW（按书自带目录切章）、HTML |
| 🎯 **续读精确到字** | 进度记「第几段的第几个字」，改字号不跳页，App 被划掉也不丢 |
| 🚫 **不用 WebView** | 很多手表没有浏览器内核 —— 本项目排版渲染全由系统完成，装得上就跑得动 |
| 📵 **完全离线** | 不申请网络权限，不需要账号，书和进度都在本机 |
| 🎨 **完整 Material 3** | 自定义 M3 色板、M3 组件、小屏字阶；深浅色切换带过渡动画 |
| ⌚ **针对小屏重做** | 44dp 触控区、圆屏安全边距、禁用手表固件层的侧滑返回 |

---

## 下载安装

**最新版本：`wearbook-watch-1.0.22.apk`**（约 1.1 MB）

前往 [Releases](../../releases) 页面下载，然后在手表或手机上安装（Android 7.0 及以上）：

```bash
adb install -r wearbook-watch-1.0.22.apk
```

- 或者把 APK 拷进手表存储，用手表自带的文件管理器点击安装
- 或者用 Android Studio 打开本目录直接 Run 到设备上

> 随包发布的 APK 用**默认 debug keystore** 做 v1 + v2 双签名，**可以直接装**。正式分发请换成自己的 keystore。

---

## 功能

### 📚 支持格式

| 格式 | 说明 |
|---|---|
| **TXT** | UTF-8 / GBK 自动识别，长文自动切章 |
| **EPUB** | 按 spine 取正文，**正文插图会显示**（点一下可放大看细节），封面缩略图 |
| **MOBI / AZW** | PalmDOC 解压，**按书自带的目录锚点切章**（目录名就是「第一章 科学边界」） |
| **HTML** | 直接解析 |

### 📥 导书：三条路都能走

1. **应用内文件管理器** —— 从 `/sdcard` 起逐级浏览，点文件夹进入、点电子书即导入。不调用系统 SAF，**没有文件选择器的手表也能用**。非电子书文件会置灰并标注「非电子书格式」，不会白点
2. **分享导入** —— 在微信 / MT 管理器里长按电子书 →「分享」→ 选腕上书。走 `contentResolver` 流，**零权限，永远可用**
3. **打开方式** —— 在其他应用里选「打开方式」→ 腕上书，直接读入（已注册 epub / mobi / azw）

### 📖 阅读体验

- **两种模式**：翻页（一屏一页）/ 滑动（整章连续上下滚，像看网页）
- **续读精确到字**：进度记「第几段的第几个字」，不是页码 —— 改字号、改行距、开关状态栏都不会跳页；App 被划掉、锁屏、进程被回收也**不会丢进度**
- **目录与书签**：按书自带目录切章，长篇小说直接跳章
- **全文搜索**：本书内搜索，点结果直接跳到命中页
- **插图放大**：正文插图点一下撑满屏幕，**单指拖动**看局部（手表双指缩放做不出来，所以不用捏合手势）
- **底部状态栏**：百分比 + 当前时间，可分开开关

### 🎨 排版与外观

- 字号 **14–26px**、行距 **1.3–2.2**，随时可调
- **日间 / 夜间各 4 套底色**：纸白 / 米黄 / 淡绿 / 浅灰 · 纯黑 / 深灰 / 深蓝 / 深棕
- 深浅色跟随系统或手动切换，切换时背景带过渡动画
- 文字两端对齐（可选）、**圆屏安全边距**
- 左右拖动可调亮度，也支持独立亮度控件

### 🔊 听读与手感

- **系统 TTS 朗读**：读完本页自动翻页，0.5–1.5 倍速，朗读时保持常亮
- **自动翻页**：5 / 10 / 20 / 30 / 60 秒，解放双手
- 翻页震动、点击屏幕两侧翻页、音量键翻页

### ⌚ 专为手表做的

- **纯原生 Compose 绘制**，不用 WebView，DEX 内无 `webkit` 引用
- 操作逻辑针对极小屏幕重新设计（不是把手机界面缩小），所有菜单按钮都是「**图标 + 文字**」，长按弹说明
- 沉浸式全屏、阅读时常亮
- **禁用手表固件层的侧滑返回**：从屏幕最左边划一下是翻页，不会误退出（返回键照常可用）

---

## 看不到 epub / txt？看这里

如果你在应用里**一个电子书文件都列不出来**，这不是 bug，是安卓的存储策略：

> 安卓 11+ 的「分区存储」规定：只申请 `READ_EXTERNAL_STORAGE` 时，用 `File.listFiles()` 只能列出音乐、图片等**媒体文件**，电子书会被系统直接隐藏。

**三个解决办法（任选）：**

1. **打开「所有文件访问」** —— 本应用已声明 `MANAGE_EXTERNAL_STORAGE`，进入导入页时会**自动跳转系统设置**，打开那个开关再返回即可（页面顶部也有常驻提示卡片和按钮）
2. **分享导入** —— 在微信 / MT 管理器里长按电子书 →「分享」→ 腕上书，**完全不需要任何权限**
3. **用专属文件夹** —— `/sdcard/Android/data/com.webook.watch/files`，这个目录任何系统、任何权限都能读写（导入页给了直达按钮）

Android 10 及以下（多数老款手表）不受此影响。

---

## 技术实现（为什么不需要 WebView）

| 能力 | 网页版 | 本版本 |
|---|---|---|
| 排版 | CSS + Range 测量 | `TextPaint.breakText` 断行 |
| 分页 | JS 二分 + 容器校验 | 纯函数 `PagePacker.pack()`（可 JVM 单测） |
| 绘制 | DOM | Compose `Canvas` 逐行 `drawText`，位置由分页结果决定 → 不会有半截字 |
| EPUB | JSZip | `java.util.zip.ZipFile` + `XmlPullParser` |
| MOBI | JS PalmDOC | Kotlin `PalmDoc.decompress` |
| 朗读 | Web Speech API | 系统 `TextToSpeech` |
| 导入 | input file / FSA | 内置文件管理器：`java.io.File` 直接遍历目录（非 SAF） |
| 存储 | IndexedDB | 应用私有目录 JSON（无需权限、无需数据库） |

**内存友好设计**：手表堆上限常常只有 96MB。EPUB 用 `ZipFile` 逐章读，插图**不预抽取、不占额外空间** —— 翻到哪一页才去压缩包里读那一两张，按屏幕宽度降采样、`RGB_565` 解码，最多缓存 4 张。

---

## 自己编译

需要 JDK 17+ 与 Android SDK（platform 34 / build-tools 34.0.0）。依赖走阿里云镜像（已在 `settings.gradle.kts` 配好）。

```bash
export JAVA_HOME="/path/to/jdk"
export ANDROID_HOME="/path/to/android-sdk"

gradle test              # 跑 JVM 单测
gradle assembleRelease   # 产物：app/build/outputs/apk/release/app-release-unsigned.apk（未签名）

# 用默认 debug keystore 强制 v1 + v2 双签名
# （AGP 8.6.1 在 minSdk ≥ 24 时默认关掉 v1，旧系统会装不上，所以要手动签）
KS="$USERPROFILE/.android/debug.keystore"
BT="$ANDROID_HOME/build-tools/34.0.0"
java -jar "$BT/lib/apksigner.jar" sign \
  --v1-signing-enabled=true --v2-signing-enabled=true --v3-signing-enabled=false \
  --ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out wearbook-watch-1.0.22.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 已知限制

- 带 DRM 的 EPUB、亚马逊 **KF8 / KFX** 不支持（会明确提示，不会静默失败）
- **PDF** 不打算支持：小屏重排体验差
- **插图放大只支持翻页模式**：滑动模式下点击插图暂不响应
- 朗读依赖手表自带的 TTS 引擎，部分精简 ROM 未内置（此时点击朗读没有声音）
- 文字两端对齐默认关闭，开启后段落末行不参与对齐

---

## 开源与许可

- **许可证**：**GNU GPL-3.0**（见 `LICENSE`）· Copyright (C) 2026 ZPW38
  你可以自由使用、修改、分发本项目，**但衍生作品必须同样以 GPL 开源** —— 不允许闭源。
  （注意：GPL **不禁止商用**，别人照样可以卖，只是必须开源。）
- **为什么选 GPL 而不是 MIT**：MIT 允许别人拿本项目做**闭源**产品。选 GPL-3.0 是为了保证腕上书及其衍生作品**永远保持开源**
- **第三方资源与依赖**
  - 界面图标：**Google Material Symbols**（Apache-2.0），转换脚本在 `tools/icons/`
  - 依赖库：AndroidX / Jetpack Compose / Kotlin 等，均为 Apache-2.0 等宽松许可（宽松协议可单向并入 GPL 项目，因此本项目以 GPL-3.0 使用它们完全合法）
  - 本项目**没有**使用任何第三方电子书解析库：EPUB 用 `java.util.zip.ZipFile` + `XmlPullParser`，MOBI / PalmDOC 是手写解压
  - 交互设计参考了开源阅读（Legado）的帮助文档。Legado 同为 GPL-3.0，本项目现在也是 GPL-3.0，因此今后可以直接借鉴其代码（引用处会注明来源）
- **权限说明**（上架应用商店时需留意）
  - `MANAGE_EXTERNAL_STORAGE`（所有文件访问）：安卓 11+ 下 `File.listFiles()` 只返回媒体文件，为了能在应用内浏览并导入电子书才需要。Google Play 对此权限审核严格，若上架可改用 SAF 或「分享到应用」
  - `VIBRATE`（翻页震动）、`READ_EXTERNAL_STORAGE`（安卓 10 及以下）
  - **无网络权限**，完全离线运行
- **签名**：随包发布的 APK 用默认 debug keystore 签名，仅供测试安装。正式发布请自建 keystore，并把 keystore 与密码**排除在仓库之外**（`.gitignore` 已屏蔽 `*.jks` / `*.keystore`）

---

<sub>历史版本的修复记录见 [CHANGELOG.md](CHANGELOG.md)。</sub>
