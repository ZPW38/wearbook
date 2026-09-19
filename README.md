# 腕上书 · 安卓手表电子书阅读器（原生风格）

因为手表**没有 WebView**，网页版（同目录的 `wearbook/`）在手表上跑不起来，所以阅读器用 **Kotlin + Jetpack Compose（Material 3）原生重写**了一遍：
不用 WebView、不用浏览器内核，排版和渲染全部由系统绘制完成。

## 亮点

- **完整 Material 3 设计**：整套界面基于 Material 3（Material You 风格），自定义 M3 色板（紫调）+ M3 组件（`Surface` / `Switch` / `Slider` / `Dialog` / 进度指示器 / FAB），并专门配了一套适合小屏的字阶。
- **深浅色双主题**：跟随系统 / 浅色 / 深色三选，切换时背景色带过渡动画；阅读底色另有日间、夜间各 4 套可选。
- **纯原生 Compose，不用 WebView**：很多手表没有浏览器内核，本项目排版与渲染全部由系统绘制完成，装得上、跑得动。
- **动效齐全**：页面切换、菜单浮层、列表增删、亮度调节都带过渡动画，不是"生硬跳变"。
- **完全离线**：不申请网络权限，不需要账号，书和进度都存在本机。

## 一、已编译好的安装包

| 文件 | 说明 |
|---|---|
| `wearbook-watch-1.0.15.apk`（约 1.08 MB） | release 包，已开 R8 压缩与资源裁剪，**v1+v2 双签名、含 32 位 armeabi-v7a**，可直接安装 |

安装方式（任选其一）：

```bash
adb install -r wearbook-watch-1.0.15.apk
```

- 或把 APK 拷进手表存储，用手表自带的文件管理器点击安装；
- 或用 Android Studio 打开本目录直接 Run 到设备上。

> 签名：release 用 **默认 debug keystore** 做 **v1 + v2 双签名**，**可以直接装**。
> 正式分发请换成自己的 keystore。
>
> ⚠️ AGP 8.6.1 在 `minSdk ≥ 24` 时会默认关掉 v1 签名，所以本工程先产出未签名 APK，
> 再用 `apksigner` 强制 v1+v2（见第四节命令），这样旧系统（Android < 7.0）也能装。

## 二、功能

**格式**：TXT（UTF-8 / GBK 自动识别，长文自动切章）、EPUB（2/3，按 spine 取正文 + 封面缩略图）、MOBI / AZW（PalmDOC 解压，按 `<mbp:pagebreak>` 切章）、HTML。
**导入**：**内置文件管理器**（不调用系统 SAF）—— 应用内从 `/sdcard` 起逐级浏览，点文件夹进入、点电子书文件即读入解析导入，支持返回上级；兼容没有系统文件选择器的手表。
非电子书文件会**置灰并标注「非电子书格式」**，点击给出提示，不会白点。

> ⚠️ **看不到 epub / txt？** 安卓 11+ 的「分区存储」规定：只申请 `READ_EXTERNAL_STORAGE` 时，用 `File.listFiles()` 只能列出音乐、图片等**媒体文件**，电子书会被系统直接隐藏（本机实测：同一目录里 3 个 epub + pdf/docx 全部不可见，只剩 2 个 mp3）。
> 解决办法：本应用已声明 `MANAGE_EXTERNAL_STORAGE`，进入导入页时会**自动跳转系统设置的「所有文件访问」开关**（页面顶部也有常驻提示卡片和按钮），打开后返回即可看到全部文件；`onResume` 会自动重新扫描。
> 另一条不需要任何权限的通道：在其他 App（微信 / MT 管理器等）里长按电子书 →「分享」或「打开方式」→ 选**腕上书**，直接导入；已注册 `ACTION_SEND`（\*/\*）与 `ACTION_VIEW`（epub / mobi / azw）。
> Android 10 及以下（多数老款手表）不受影响，仍是原来的 `READ_EXTERNAL_STORAGE` 运行时申请。
**阅读**：自动分页、进度记忆、章节切换、浅色/深色/跟随系统、圆屏安全边距、长按书卡删除。
**阅读界面菜单（1.0.5 重做：全部「图标 + 文字」，长按还有说明）**：

| 位置 | 按钮 | 作用 |
|---|---|---|
| 顶部 | 章节名 / 书本图标 | 打开目录（可跳章、看书签） |
| 左侧 | 亮度条 | 顶部有「跟随系统」开关，关掉后拖动调节；可在「设置」里隐藏 |
| 底部第 1 行 | 上一章 · 页数进度条 · 下一章 | 快速换章，进度条显示章/页/百分比 |
| 底部第 2 行 | 搜索 / 自动翻页 / 书签 / 夜间 | 全文搜索（只搜本机缓存，点结果跳到那一页）、自动翻页（5·10·20·30·60 秒 ↔ 关闭）、把当前页加书签、日夜切换 |
| 底部第 3 行 | 目录 / 朗读 / 界面 / 设置 | 目录与书签、朗读（点一下开始、长按进朗读设置）、排版设置、其它设置 |

- 「界面」= 排版：字号 14–26px、行距 1.3–2.2、日间/夜间各 4 套底色（纸白/米黄/淡绿/浅灰 · 纯黑/深灰/深蓝/深棕）、文字两端对齐、共用布局开关（关掉后夜间单独记一套字号行距）
- 「设置」= 其它：屏幕方向（跟随系统/竖屏/横屏）、自动翻页秒数、阅读时常亮、隐藏状态栏、音量键翻页、点击屏幕两侧翻页、翻页震动、圆屏安全边距、显示亮度控件
- 任意菜单按钮**长按**会弹出该按钮的功能说明；首次进入阅读页也会给一次操作提示
- **安卓 10 手表上一个文件都列不出来**（1.0.15）：targetSdk 34 装在 Android 10 上同样受分区存储限制，光有 `READ_EXTERNAL_STORAGE` 时 `File.listFiles()` 在 `/sdcard` 返回空 —— 之前代码里"Android 10 及以下不需要特殊权限"的判定是错的。现在清单加了 `android:requestLegacyExternalStorage="true"`（只在 Android 10 生效，Android 11+ 自动忽略，安卓 11+ 仍走「所有文件访问」）。另外补了两条兜底：① 根部读不到任何文件时，导入页直接提示"系统没放行读取存储"并给「打开专属文件夹」按钮 —— `/sdcard/Android/data/<包名>/files` 这个目录任何系统、任何权限都能读写；② 空目录页写明可以从 MT 管理器等文件管理器「分享 / 打开方式」选腕上书导入（走 `contentResolver` 流，零权限）。
- **导入页卡在空目录出不来**（1.0.13）：那个「.. 返回上级目录」原来放在文件列表里，目录一空列表不渲染、入口就跟着消失了。现在标题栏固定一个「↑ 返回上级」按钮，空目录页面也补了一个；顺便把浏览的根目录固定成 `/sdcard`（之前会因为 `File.canRead()` 为 false 而悄悄回退到 App 私有目录，看着像"文件都没了"）。
- **点开关/滑块，状态不刷新，退出再进才好**（1.0.14）：设置值存在普通对象里（不是 Compose 状态），而 Compose 会**跳过参数没变化的界面**，导致开关点了值改了、界面却没重新读。现在 `Settings` 带一个可观察的修订号 `rev`，改设置时自增，各面板订阅它。
- **滑动阅读模式**（1.0.12）：在「界面（排版）」面板最上面选「翻页（一屏一页）」或「滑动（连续上下滚）」。
  - 滑动模式 = 整章连续上下滚动，像看网页；实现是 `LazyColumn` 把正文按每 24 行切块渲染，**只画可见的几块**，所以长章节也不会一帧画几千行。
  - 上下拖动正常滚动；点屏幕左右两侧 = 滚一屏；滚到章末有「下一章 ▶」按钮；菜单里的进度、书签、朗读仍按「页」工作（滚动位置会折算成页码）。
  - 切换模式或换章时自动定位到刚才读到的地方；阅读进度照常记忆。
- **「其它设置」里几项开关点了没反应**（1.0.11，实测设备华为 Mate 40 Pro）：
  - 屏幕方向 / 隐藏状态栏 / 阅读时常亮 / 圆屏安全边距 —— 这四项都要调用 `applySystemSettings()` 去真正改窗口，但传给面板的回调写成了**只应用亮度**，等于白点。已改为传 `applySystemSettings()`。
  - 翻页震动 —— 清单里**漏了 `android.permission.VIBRATE`**，系统直接静默忽略。已补上（普通权限，声明即可）。
  - 「显示亮度调节控件」本身是好的，只是要收起面板、再点屏幕中间打开阅读菜单才看得到左侧亮度条，面板里补了一行说明。音量键/震动的说明文字也补上了。
- **页面标题栏看不见（尤其投屏 / 多屏协同时）**（1.0.10）：
  - 根容器从「Box + background」改成 **`Surface(color = background, contentColor = onBackground)`**：保证整屏底色一定被画出来（投屏/桌面模式下不会透出窗口白底），同时给整棵树一个与背景配套的文字色。
  - 「我的书架」「导入图书」「设置」三条标题栏都加了**实底色 `surfaceContainer`**，标题文字与图标显式用 `onSurface` —— 不再依赖"碰巧配套"的默认色，任何主题下都分层清晰。
  - 阅读页工具栏同理（1.0.9 已改成由阅读底色现算）。
- **深色模式菜单看不清 / 滑杆拖不动**（1.0.9，两个真 bug）：
  - 阅读页顶部/底部工具栏原来用 `MaterialTheme.colorScheme.surface`，与自定义阅读底色不配套，深色下菜单文字糊在背景里 → 改成**由阅读底色和文字色现算**：工具栏底色 = `lerp(底, 字, 0.12)`，图标/文字/进度条一律用阅读文字色，深浅主题下都保证对比度。
  - 排版/设置里的滑杆原来每拖动一像素就写一次 SharedPreferences、并触发整棵 Compose 树重组（书架设置页还会顺带重设窗口属性），手表上直接卡成「拖不动、点了要重进界面才响应」 → 改成**本地状态驱动**：拖动只更新本地值（数字实时变），`onValueChangeFinished` 松手时才提交设置。阅读页与书架设置页两处 `SliderRow` 都改了。
- **禁用手表侧滑返回**（1.0.8 关键修复）：手表（Wear OS 系固件）的"从左边缘划一下就返回"是**固件层的右滑关闭手势**，不走 Android 的返回手势，所以只设手势排除区拦不住。正解是在主题里加：
  ```xml
  <item name="android:windowSwipeToDismiss">false</item>
  ```
  （`res/values/themes.xml`，已验证编译进包：`aapt2 dump resources` 里 `Theme.WearBook` 有 `0x010103f3=false`，minSdk28 变体里也有）。普通安卓手机不认识这个属性会被忽略，无副作用。
  1.0.7 的左边缘 24dp 手势排除区（`ViewCompat.setSystemGestureExclusionRects`）作为补充保留，两套一起用。
- **禁用左边缘侧滑返回**（1.0.7）：手表常见的"从左边缘划一下就返回"。做法是把**屏幕左边缘 24dp 的一条窄带**声明为系统手势排除区（`ViewCompat.setSystemGestureExclusionRects`），系统不再把它当返回手势，滑动原样交给应用 → 阅读时从最左边划是翻页，不会误退出。
  - 坑：排除区**只能给窄带**，整屏矩形会被系统按尺寸上限裁剪掉、完全失效（1.0.7 之前就是这么写的）；窗口重新获得焦点时会再刷一次。
  - 设置页有开关（默认开）与说明；少数手表把手势做在系统底层、不理会应用声明，那种需要在手表「设置 → 手势 / 系统导航」里关，设置页给了直接打开系统设置的按钮。
  - 注意：这不等于禁用"返回"本身，返回键和在书架连按两次退出照常保留。
- 1.0.6 修的问题：① 菜单原来是**整屏 94% 不透明遮罩**，夜间模式下正文被压成一片黑（看着像「字变黑了」）→ 改成只加顶部/底部两条工具栏，正文全程可见，点中间收起菜单；② 「界面」和「设置」原来是同一个齿轮、「自动翻页」和「目录」都是书 → 换成 format_size / tune / autoplay / toc，面板关闭按钮也不再是垃圾桶（换 close）；③ 左侧亮度条的「跟随系统」文字被开关压住 → 改成竖排（文字→开关→竖向滑杆→百分比）；④ 设置面板内容超出 300dp 固定高度导致开关重叠 → 面板高度改 68% 且内容可滚动；⑤ 夜间 4 套底色的文字整体提亮（纯黑版 #B9B9B9 → #D8D8D8）
**示例书已移除**（1.0.6）：不再提供「载入示例书」入口，升级后会自动清掉书架里旧的示例书（fmt 为 demo 的那些），只留用户自己导入的书。
**听读与手感**：系统 TTS 朗读（读完本页自动翻页，0.5–1.5 倍速，朗读时保持常亮）、翻页震动、书签、自动翻页。
**手表优化**：230KB 级小屏布局、44dp 触控区、圆屏模式、沉浸式全屏、阅读时常亮、无网络权限（完全离线）。

## 三、关键实现（为什么不需要 WebView）

| 能力 | 网页版 | 原生版 |
|---|---|---|
| 排版 | CSS + Range 测量 | `TextPaint.breakText` 断行 |
| 装页 | JS 二分 + 容器校验 | 纯函数 `PagePacker.pack()`（可 JVM 单测） |
| 绘制 | DOM | Compose `Canvas` 逐行 `drawText`，位置由分页结果决定 → 不会有半截字 |
| EPUB | JSZip | `java.util.zip.ZipFile` + `XmlPullParser` |
| MOBI | JS PalmDOC | Kotlin `PalmDoc.decompress` |
| 朗读 | Web Speech API | 系统 `TextToSpeech` |
| 导入 | input file / FSA | 内置文件管理器：`java.io.File` 直接遍历目录（非 SAF） |
| 存储 | IndexedDB | 应用私有目录 JSON（无需权限、无需数据库） |

## 四、自己编译

本机环境（已配好）：

- JDK 21：`C:\Program Files\Android\openjdk\jdk-21.0.8`
- Android SDK：`C:\Users\18557\AppData\Local\Android\Sdk`（platform 34 / build-tools 34.0.0）
- Gradle 8.7：`C:\Users\18557\.workbuddy\binaries\gradle\gradle-8.7`
- 依赖走阿里云镜像（已在 `settings.gradle.kts` 配好，不依赖外网）

```bash
export JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8"
export ANDROID_HOME="C:/Users/18557/AppData/Local/Android/Sdk"

gradle test              # 跑 6 项 JVM 单测
gradle assembleRelease   # 产物：app/build/outputs/apk/release/app-release-unsigned.apk（未签名）

# 用默认 debug keystore 强制 v1 + v2 双签名（AGP 8.6.1 在 minSdk≥24 时默认不给 v1）
KS="$USERPROFILE/.android/debug.keystore"
BT="$ANDROID_HOME/build-tools/34.0.0"
java -jar "$BT/lib/apksigner.jar" sign \
  --v1-signing-enabled=true --v2-signing-enabled=true --v3-signing-enabled=false \
  --ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out wearbook-watch-1.0.15.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

## 五、验证情况

- ✅ Kotlin 编译、R8 混淆、资源裁剪全部通过
- ✅ JVM 单测 6/6：分页"每页不超高度""所有行恰好覆盖一次""段间距计入""空输入不崩"，PalmDOC 字面量/回溯解压
- ✅ `aapt2 dump badging` 核验：minSdk 24、仅声明触摸屏、**无任何联网权限**、DEX 内无 `webkit` 引用
- ✅ `apksigner verify`：`Verifies = true`，v2 方案生效；`jarsigner -verify`：v1（JAR）签名有效 → **v1+v2 双签名**
- ✅ `native-code: 'arm64-v8a' 'armeabi-v7a'`，含 32 位 armeabi-v7a
- ✅ 图标全部来自官方 **Material Symbols**（下载 SVG → `icons/svg2vd.py` 转成 `res/drawable/ic_*.xml` 矢量图），风格统一
  - 修过两次矢量转换 bug：① 原 SVG 的 `viewBox="0 -960 960 960"`，需把 **绝对** y 坐标 +960 平移；② 路径**开头的相对 `m`** 按 SVG 规范要当绝对点处理（否则图标跑到可视区外）。已用无头 Chrome 把「原版 SVG vs 转换结果」并排渲染比对，13 个图标逐一一致（见 `icons-verify.png`）
- ✅ 启动图标：自适应图标前景（menu_book 白色矢量，缩放 0.58 收在 72dp 安全圆内）+ 五档密度 `mipmap-*/ic_launcher.png`（紫底白书），新旧系统都正常显示
  - 修过的坑：工程里曾残留一张 **纯白的 `res/drawable-nodpi/ic_launcher_foreground.png`（432×432）**，它在资源匹配时盖住了同名的矢量前景 → 图标变成一块白板。已删除该文件，现在 `drawable/ic_launcher_foreground` 只有矢量一个候选（`aapt2 dump resources` 已核对）
  - 用 `icons/render_adaptive.py`（无头 Chrome 按自适应图标规则合成 + 套圆/圆角方遮罩 + 画 72dp 安全圈）生成 `icons/launcher-check.png` 肉眼确认；`icons/png_probe.py` 直接解码 APK 内的 5 张 PNG 统计像素，确认是紫底白书而非空白
- ✅ 翻页横向滑动、阅读浮层淡入+上滑、设置页入场、导入列表项增删动画均已接入
- ✅ 深浅色模式：根容器铺 `MaterialTheme` 背景色并随主题切换（背景与文字一起变）
- ✅ 阅读界面（1.0.5）：新增 search / bookmark / dark_mode 三个官方 Material Symbols 图标，`icons/render_check.py` 渲染比对 17 个图标「官方 SVG vs 转换结果」逐一一致
- ✅ JVM 单测 6/6 通过（`gradle testReleaseUnitTest`，分页/解压逻辑未受影响）
- ✅ 已禁用"下滑/侧滑返回"：`enableOnBackInvokedCallback=false` + 自定义返回键 + 系统手势排除区
- ✅ 文件识别（1.0.4）：定位到安卓 11+ 分区存储导致 `File.listFiles()` 只返回媒体文件 → 增加「所有文件访问」引导（自动跳系统设置 + 常驻提示卡片 + 返回后自动重扫），并注册分享/打开方式入口；`aapt2 dump xmltree` 已核对 `MANAGE_EXTERNAL_STORAGE` 与两个 intent-filter 均正确打进包里

## 六、已知限制

- 带 DRM 的 EPUB、亚马逊 KF8 / KFX 不支持（会明确提示）
- PDF 不打算支持：小屏重排体验差
- 正文按纯文本排版：EPUB 里的插图暂不显示（只保留封面）
- 朗读依赖手表自带的 TTS 引擎；部分精简 ROM 未内置，此时点击朗读无声音
- 文字两端对齐是「界面」面板里的可选开关，默认关闭（开启后逐字均分字距，段落末行不对齐）

## 七、开源与许可

- **许可证**：MIT（见 `LICENSE`）。发布前记得把版权人换成你自己的名字。
  想换成 Apache-2.0（同样宽松、额外含专利授权）或 GPL-3.0（要求衍生作品也开源）只需替换该文件。
- **第三方资源与依赖**
  - 界面图标：**Google Material Symbols**（Apache-2.0）。原始 SVG 与转换脚本在 `tools/icons/`，转换产物是 `app/src/main/res/drawable/ic_*.xml`。
  - 依赖库：AndroidX / Jetpack Compose / Kotlin 等，均为 Apache-2.0 等宽松许可，可自由分发。
  - 本项目**没有**使用任何第三方电子书解析库：EPUB 用 `java.util.zip.ZipFile` + `XmlPullParser`，MOBI/PalmDOC 是手写解压。
  - 交互设计参考了开源阅读（Legado）的帮助文档，但**没有拷贝它的代码**。注意 Legado 是 GPL-3.0：以后若想借用它的代码，整个项目需要改成 GPL-3.0。
- **权限说明**（将来上架应用商店时要留意）
  - `MANAGE_EXTERNAL_STORAGE`（所有文件访问）：安卓 11+ 下 `File.listFiles()` 只返回媒体文件，为了能在应用内浏览并导入电子书才需要它。Google Play 对此权限审核严格，若上架可改用 SAF 或「分享到应用」的方式。
  - `VIBRATE`、`READ_EXTERNAL_STORAGE`（安卓 10 及以下）。
  - **无网络权限**，完全离线运行。
- **签名**：随包发布的 APK 用**默认 debug keystore** 签名，仅供测试安装。正式发布请自建 keystore，并把 keystore 与密码**排除在仓库之外**（`.gitignore` 已屏蔽 `*.jks` / `*.keystore`）。
- **提交前自查**：不要把 `local.properties`（含本机 SDK 路径）、`app/build/`、`*.apk`、keystore、日志提交进仓库 —— `.gitignore` 已覆盖这些。
