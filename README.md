# Colour Clock — thecolourclock.com 的原生 Android 复刻

把 <http://www.thecolourclock.com/> 复刻成 Android APP。**不是网页套壳**：没有 WebView、没有 AndroidX/Kotlin、没有任何第三方运行时依赖，整个界面由一个自定义 `View` 直接用 `Canvas` 画出来。

## 四项要求的落实

| 要求 | 做法 |
| --- | --- |
| 原生，不要网页套壳 | 全部代码为 3 个 framework Java 文件：[MainActivity.java](app/src/main/java/com/andraid/colourclock/MainActivity.java)（窗口/全屏/旋转/insets）、[ClockView.java](app/src/main/java/com/andraid/colourclock/ClockView.java)（唯一视图，绘制+交互）、[ColourEngine.java](app/src/main/java/com/andraid/colourclock/ColourEngine.java)（取色算法）。`AndroidManifest.xml` 不声明任何权限，也不含 WebView。 |
| 支持 4.X.X 一直到当前版本 | `minSdk 14`（Android 4.0 ICS）→ `compileSdk/targetSdk 36`（Android 16）。所有新 API 都用 `Build.VERSION.SDK_INT` 分支隔离，`aapt2 dump badging` 确认 `minSdkVersion:'14'`。 |
| 运行开销极小 | 空闲时 60 ms 轮询一次（最后一位十六进制每 62.5 ms 才变），只有时间/颜色变化或动画进行中才 `invalidate()`；动画结束即停；无后台服务、无 Wakelock、无 Alarm、无网络。`onPause()` 立刻停止轮询。release APK 67,854 B，`classes.dex` 25,332 B，里面只有 8 个类。 |
| 启动即全屏，竖屏/横屏跟随系统自动旋转 | 主题继承 `@android:style/Theme.NoTitleBar.Fullscreen`，再叠加三代全屏 API：API 30+ 用 `WindowInsetsController`，API 19–29 用 `SYSTEM_UI_FLAG_*` + `IMMERSIVE_STICKY`，API 14–18 用旧 flag。颜色铺满整个屏幕（顶到四个角，不留页面色边框），状态栏/导航条/刘海的安全区只用来约束内容，不裁剪颜色。`android:screenOrientation="user"` 同时允许竖横两向并尊重系统的自动旋转开关；`android:configChanges` 覆盖旋转相关的全部变更，因此转屏不会重建 Activity，颜色不会闪。刘海屏在 API 28+ 走 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`。 |

## 取色算法的一致性

站点的 `clock.js` 把时钟映射成 6 个十六进制位：小时的区间/小时的小数、分钟的区间/分钟的小数、秒的区间/秒的小数，其中小时不看秒、分钟不看毫秒。`ColourEngine.hex()` 保留与原 JS 完全相同的运算顺序（`Math.round`、`x - Math.floor(x)`、`* 15`），因此结果逐位相同。

验证方式：把站点原版 `getHex()` 直接在 Node 里跑一遍，与 Java 实现交叉比对，覆盖 24 小时 × 10 分钟 × 13 秒 × 10 个毫秒取值 = **31,200 组输入，0 处不一致**；另外与线上页面同时刻取样（14:03:13 → `961535`）也对得上。

## 字号设置

表头右上是两枚圆片：`A` 管字号，`i` 管关于。点 `A` 会在读数下方滑出一条手画的滑杆（轨道 + 已选段 + 每 20% 一道刻度 + 滑块 + 一大一小两个 `A` 当图例 + 百分比读数），拖动即改字号，抬手写入 `SharedPreferences`（文件 `colour_clock`，键 `readout_scale`），下次启动自动恢复。滑杆和 about 面板各占整屏，打开一个必然收起另一个。

- 取值 40%–160%，步长 5%，默认 100%；100% 就是站点原本的 `17.5vmin`，所以升级后不去动滑杆，读数、颜色、胶囊都和 1.0.1 一样，唯一的变化是表头多出那枚 `A` 圆片（表头的横线相应缩短到它左边）。
- 倍数作用在两个"防裁切"上限**之前**：`readoutSize = clamp(min(w,h) × 0.175 × pct, 16dp, min(宽度放得下的最大值, 色带高 × 0.55))`。字号因此只会在到达上限后停止变大，永远不会把数字切掉。
- 现实中的不对称（数字是离屏渲染逐档量出来的，测试机 1080×2340 px、420dpi）：竖屏下 `17.5vmin` 已经几乎贴着**宽度**上限——默认档 `00:00:00` 横向占 834 px，105% 起就固定在 882 px，所以往上调只有约 6% 的余地，往下可以缩到 40%（333 px）。横屏宽度绰绰有余，上限换成色带高度，而量程顶端 160% 还没碰到它，因此能一路长到 1299 px，是默认的 1.56 倍。这不是 bug，是"永远不裁切"这条保证的代价。
- 滑杆的点击区落在读数色带内部，所以命中测试先问滑杆、再问胶囊和大字；上下留的余量刻意不对称（上方 20dp、下方 30dp），否则最大字号时数字会压进滑杆的点击区，点大字就变成拖动而不是切模式。
- 依旧没有新增依赖：滑杆是 `Canvas` 画的，动画是 260 ms 的三次缓出，与 about 面板同一套手写补间，拖动和吸附都在 `onTouchEvent` 里做。

## 渲染验证

界面用 Robolectric 4.17 在 JVM 上离屏绘制真实 `ClockView`（`GraphicsMode.NATIVE`，像素级）来检查，见 [ClockViewRenderTest.java](app/src/test/java/com/andraid/colourclock/ClockViewRenderTest.java)，12 项全部通过（`gradle testDebugUnitTest`）。几何只有两组 fixture：1080×2340 px 竖屏与 2340×1080 px 横屏，都是 420dpi（qualifier 写成 411dp×914dp）。

- 竖屏 TIME 模式：颜色顶到屏幕四边四角（无页面色边框）、卡片色 = 当前时刻的十六进制色、大字读数不被裁切；
- 点 COLOUR 胶囊：模式切换、读数变为 `#rrggbb`，颜色仍顶到四边；
- 状态栏安全区 36dp 时：颜色依旧铺满四边，但表头随安全区下移——在旧表头位置点击不会打开面板，在新位置会（这条直接对应 Android 13 真机截图暴露的问题）；
- 点右上 ⓘ：卡片收成 80dp 横条、下方回到页面底色并绘出 about 文案、文案不越界；
- 横屏（宽布局，含表头横线）：读数仍在内容安全框内；
- 横屏 about：文案落在颜色让出的右侧条带里，且整块不超出面板底部；
- 中文正文断行：没有空格可断的中文按"避头点"规则换行——`，。、；：！？）》」』】〉…—` 等不会出现在行首，`（《「【〈` 等不会出现在行尾，且断行前后逐字符对账，一个字也不丢；
- 字号滑杆：点 `A` 滑条出现，从轨道左端拖到右端得到 160%、拖回左端得到 40%，两者横向跨度比超过 1.5 倍；数值落进 `SharedPreferences`，新建的 `ClockView` 读回同一个值；再点一次 `A` 滑条收回（`sliderResizesTheReadoutAndStaysRemembered`）；
- 竖屏 160%：数字仍然完整不裁切，且最低一行停在滑杆点击区上方 20dp 之外（`largestScaleStaysInsideTheContent`）；
- 横屏 160%：同样不裁切、同样让开滑杆，并且在这一屏最挤的状态下把滑杆打开，轨道与滑块依然画得出来（`landscapeLargestScaleClearsTheSlider`）；
- 两个面板互斥：先开滑杆再点 ⓘ，about 文案正常出现，关闭 about 后滑杆已经不在原位，说明确实被收掉而不是藏在后面（`sliderGivesWayToTheAboutPanel`）；
- 夜间：页面变黑、墨色跟随主题。

渲染出的 PNG 在 `app/build/render-check/`。

## 4.X 兼容性审计

`gradle lintDebug` 的 `NewApi` 检查现在为 **0 个 Error**，剩 7 条 Warning，逐条都是有意保留的：

- `InlinedApi` ×3：`SYSTEM_UI_FLAG_FULLSCREEN` / `SYSTEM_UI_FLAG_LAYOUT_*` 是编译期内联的整型常量，在 API 14/15 上会被框架忽略，不会崩。
- `UseRequiresApi` ×2：`InsetsReader`（API 21）和 `Cutout`（API 28）用了 `@TargetApi` 而不是 `@RequiresApi`。这里要的正是"只在方法体里判断版本、类本身照样能加载"的语义，`@RequiresApi` 只是把它传播给调用方，对本项目没有意义。
- `DiscouragedApi` ×1：`android:screenOrientation="user"` 在 Android 16 的大屏设备上可能被系统忽略，这只会让它更自由地跟随旋转，与需求一致；界面本身对竖横两向都是自适应的。
- `ClickableViewAccessibility` ×1：1.1.0 加了拖动之后，`ClockView.onTouchEvent()` 仍然在 ACTION_UP 里调用 `performClick()`，但没有*覆写*它，于是 lint 提示无障碍服务无法拦截点击。整个界面是 Canvas 画的，本来也没有可遍历的无障碍节点，这条留作已知限制。

审计过程中修掉了三个真会在旧机器上炸的问题：

- `WindowInsets.getSystemWindowInsets()` 实际是 API 30 才有的方法，原来在 21–29 分支里被调用 → 改用 `getSystemWindowInsetLeft/Top/Right/Bottom`（API 20+）。
- `DisplayCutout` 是 API 28（Android 9）引入的，原来的 `>= 29` 判断会让刘海屏的 Android 9 设备拿不到安全区 → 改为 28，并把这段单独拆成 `@TargetApi(28)` 的 `Cutout` 内部类，只有真正执行到才会加载该类。
- 全屏 flag 走三代 API：API 30+ 用 `WindowInsetsController`，API 19–29 用 `SYSTEM_UI_FLAG_*` + `IMMERSIVE_STICKY`，API 14–18 只用旧 flag（`IMMERSIVE_STICKY` 本身是 API 19，已单独判断）。

## 与网站有意不同的地方

1. **字体**：原站用商业字体 Aeonik Mono（CoType），这里改用系统等宽字体 `Typeface.MONOSPACE`，about 面板里也如实写明这一点。
2. **胶囊文字**：网页上 TIME / COLOUR 的文字只在 hover 时出现，触屏没有 hover，所以这里常驻显示。
3. **对比度兜底**：原站在午夜前后会出现"白底白字"（时钟颜色用页面色绘制，而页面色恰好接近卡片色）。这里加了 WCAG 式的对比度检查（`contrast()` / `readable()`），低于 2.1 时改用另一极值或推亮/推暗，保证文字始终可读。
4. **交互**：点大字读数也能在 TIME / COLOUR 间切换（网页只有胶囊和键盘快捷键）。
5. **只有 24 小时制**：与原站一致，没有 12 小时制开关。网页上也不给调字号，多出来的设置只有[字号滑杆](#字号设置)这一项。
6. **不常亮**：没有申请 `KEEP_SCREEN_ON`，省电优先；需要常亮可以加一行 `getWindow().addFlags(FLAG_KEEP_SCREEN_ON)`。
7. **时区**：跟随设备时区与夏令时，和网页的 `Date` 行为一致。
8. **满屏铺色**：原站的色块四周留有页面空白并带圆角，这里按"启动即全屏"的要求把颜色直接铺到屏幕四边四角，圆角去掉，页面空白只保留给内容（表头、读数、胶囊）当安全边距。因此 ⓘ 展开的 about 面板在横屏是右侧条带、竖屏是顶部横条，条带以外才回到页面底色。横屏面板高度放不下全部文案时，整块（标题/正文/注释/署名）按比例一起缩小到放得下为止，网页上没有这一步。
9. **about 面板正文**：面板标题、字体说明、时区说明与 `designwithjack.com` 署名保留原站内容，正文已按需求换成中文的离线与隐私承诺。中文没有空格可以断行，`wrap()` 因此在按词换行之外实现了避头点规则；标题字距由逐字绘制手工加出（`setLetterSpacing()` 是 API 21，`StaticLayout` 跨 API 14↔36 用不上），整套排版都是手写的。

## 构建

本机工具链在 `D:\Qoder\toolchain`（Temurin JDK 17.0.20.1、Gradle 8.14.6、Android SDK platform 36 + build-tools 36.0.0），仓库内 `local.properties` 已指向它。Android Studio 直接打开本目录也可以（AGP 8.13.2）。

```bash
export JAVA_HOME="D:\\Qoder\\toolchain\\jdk-17"
export ANDROID_HOME="D:\\Qoder\\toolchain\\android-sdk"
"/d/Qoder/toolchain/gradle/bin/gradle.bat" --no-daemon assembleDebug assembleRelease testDebugUnitTest
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`（78,913 B，已用 debug key 签名，可直接安装）
- `app/build/outputs/apk/release/app-release.apk`（67,854 B，R8 混淆 + 资源收缩 + PNG crunch，同样用 debug key 签名，便于本机验证；上架请换自己的签名）

安装到手机：

```bash
"/d/Qoder/toolchain/android-sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

`settings.gradle` 里把 `maven.aliyun.com` 排在 `google()` / `mavenCentral()` 之前，国内网络下载依赖会快很多；海外构建可以忽略。

debug 包比 release 包大 11 KB，并不是内容多了。把两个 APK 的 zip 结构逐字节拆开算（按 central directory 给出的每个 local header 偏移复位，逐段前进）：

| | debug | release |
| --- | --- | --- |
| 本地文件头（28 / 29 个条目） | 1,964 B | 1,299 B |
| 压缩后的条目数据 | 70,446 B | 60,700 B |
| 条目之间的对齐填充 | 0 B | 0 B |
| APK Signing Block | 4,096 B | 4,096 B |
| Central directory + EOCD | 2,407 B | 1,759 B |
| 合计 = 文件大小 | **78,913 B** | **67,854 B** |
| 其中 `classes.dex`（未压缩） | 35,488 B（未混淆，13 个类） | 25,332 B（R8，8 个类） |

11,059 B 的差正好能对账：条目数据 9,746 B = 未混淆 dex +5,980、启动图标 PNG 没被 crunch +3,044、`resources.arsc` +732、签名与清单 +106，再减去 release 才多出的 `META-INF/version-control-info.textproto` −116；其余 1,313 B 是条目名长度（release 把 `res/mipmap-xxxhdpi/ic_launcher.png` 这类路径收缩成 `res/o-.png`，本地头省 665 B、central directory 省 648 B）。

需要更正一条旧账：1.0.1 的 README 里写"debug 包有 17,488 B 的条目间对齐空隙，最大一段 14,425 B 空在 `AndroidManifest.xml` 前面"。那是脚本读错了 zip——顺序走 local header 时走进签名块就错位了，回来后的"空隙"是假的。按 central directory 的偏移重算，这次两个包的条目之间填充都是 0 B。同一次修正也解释不了 94,303 → 78,913 的回落（1.0.1 的 debug 产物已被覆盖，无法复测），所以体积请一律以 release 包为准。

## 版本

当前是 **1.1.0**（`versionCode 3`）。基线 **v1.0.0**（`versionCode 1`）是 2026-11-05 在 Android 13 真机上确认「颜色铺满到屏幕四边、系统栏隐藏」通过的那一版，已用 `git tag -a v1.0.0` 锚定。

后续按 <https://semver.org/> 递增 `versionName`：

| 位 | 什么时候加 | 本项目里的例子 |
| --- | --- | --- |
| MAJOR | 与旧版不兼容的改动（换取色算法的输入语义、改应用 ID、删掉已有交互） | `2.0.0` |
| MINOR | 向后兼容的新功能（12 小时制、常亮开关、主题） | `1.1.0` 字号滑杆 |
| PATCH | 只修 bug，不加功能（安全区算错、文案溢出、某个 API 等级崩溃） | `1.0.1` |

`versionCode` 与它无关，每发一版只 +1（Android 靠它判断能否覆盖安装），规则写在 [app/build.gradle](app/build.gradle) 的 `defaultConfig` 上方。

每次升版的动作固定三步：改 `versionName` / `versionCode` → 提交 → `git tag -a vX.Y.Z`。

1.1.0 新增[字号设置](#字号设置)：表头多一枚 `A` 圆片，点开是一条手画滑杆，40%–160%、步长 5%、默认 100%，数值存进 `SharedPreferences`。默认档保持 1.0.1 的排版，既没有改取色算法也没有删掉任何既有交互，属于向后兼容的新功能，因此按 MINOR 递增，`versionCode` 同步 +1。渲染测试从 8 条增加到 12 条。

1.0.1 的改动只有一处：ⓘ 面板正文 `about_body` 换成中文的离线与隐私声明（标题、字体说明、时区说明、署名保留）。顺带补上了中文断行的避头点规则和一条对应的渲染测试，属于文案与排版修复，没有新增用户可见的功能，所以按 PATCH 走。

## 体积与依赖

release 包的 `classes.dex` 25,332 B（debug 不混淆时是 35,488 B），里面只有 8 个类：`MainActivity` 加 `a.a`…`a.g` 七个被 R8 改名的类（`dexdump` 逐条列出；未混淆的 debug 包能看到它们原本是 `ClockView`、`ColourEngine` 和几个内部类与 `R`）。在 dex 的原始字节里搜 `androidx/`、`kotlin/`、`android/webkit`、`com/google/android` 四个前缀，命中都是 0 次。`aapt2 dump badging` 确认 `versionCode:'3'`、`versionName:'1.1.0'`、`minSdkVersion:'14'`、`targetSdkVersion:'36'` 且没有任何 `uses-permission`。`gradle.properties` 中的 `android.useAndroidX=true` 只是为了让 Robolectric 的测试夹具（它自己依赖 `androidx.test:monitor`）能跑起来，不会把 AndroidX 带进 APK；`app/src/test/AndroidManifest.xml` 里的 `tools:overrideLibrary` 同理，只作用于单测的清单合并。
