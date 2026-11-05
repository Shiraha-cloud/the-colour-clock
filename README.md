# Colour Clock — thecolourclock.com 的原生 Android 复刻

把 <http://www.thecolourclock.com/> 复刻成 Android APP。**不是网页套壳**：没有 WebView、没有 AndroidX/Kotlin、没有任何第三方运行时依赖，整个界面由一个自定义 `View` 直接用 `Canvas` 画出来。

## 四项要求的落实

| 要求 | 做法 |
| --- | --- |
| 原生，不要网页套壳 | 全部代码为 3 个 framework Java 文件：[MainActivity.java](app/src/main/java/com/andraid/colourclock/MainActivity.java)（窗口/全屏/旋转/insets）、[ClockView.java](app/src/main/java/com/andraid/colourclock/ClockView.java)（唯一视图，绘制+交互）、[ColourEngine.java](app/src/main/java/com/andraid/colourclock/ColourEngine.java)（取色算法）。`AndroidManifest.xml` 不声明任何权限，也不含 WebView。 |
| 支持 4.X.X 一直到当前版本 | `minSdk 14`（Android 4.0 ICS）→ `compileSdk/targetSdk 36`（Android 16）。所有新 API 都用 `Build.VERSION.SDK_INT` 分支隔离，`aapt2 dump badging` 确认 `minSdkVersion:'14'`。 |
| 运行开销极小 | 空闲时 60 ms 轮询一次（最后一位十六进制每 62.5 ms 才变），只有时间/颜色变化或动画进行中才 `invalidate()`；动画结束即停；无后台服务、无 Wakelock、无 Alarm、无网络。`onPause()` 立刻停止轮询。release APK 66,301 B，`classes.dex` 22,452 B，里面只有 8 个类。 |
| 启动即全屏，竖屏/横屏跟随系统自动旋转 | 主题继承 `@android:style/Theme.NoTitleBar.Fullscreen`，再叠加三代全屏 API：API 30+ 用 `WindowInsetsController`，API 19–29 用 `SYSTEM_UI_FLAG_*` + `IMMERSIVE_STICKY`，API 14–18 用旧 flag。颜色铺满整个屏幕（顶到四个角，不留页面色边框），状态栏/导航条/刘海的安全区只用来约束内容，不裁剪颜色。`android:screenOrientation="user"` 同时允许竖横两向并尊重系统的自动旋转开关；`android:configChanges` 覆盖旋转相关的全部变更，因此转屏不会重建 Activity，颜色不会闪。刘海屏在 API 28+ 走 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`。 |

## 取色算法的一致性

站点的 `clock.js` 把时钟映射成 6 个十六进制位：小时的区间/小时的小数、分钟的区间/分钟的小数、秒的区间/秒的小数，其中小时不看秒、分钟不看毫秒。`ColourEngine.hex()` 保留与原 JS 完全相同的运算顺序（`Math.round`、`x - Math.floor(x)`、`* 15`），因此结果逐位相同。

验证方式：把站点原版 `getHex()` 直接在 Node 里跑一遍，与 Java 实现交叉比对，覆盖 24 小时 × 10 分钟 × 13 秒 × 10 个毫秒取值 = **31,200 组输入，0 处不一致**；另外与线上页面同时刻取样（14:03:13 → `961535`）也对得上。

## 渲染验证

界面用 Robolectric 4.17 在 JVM 上离屏绘制真实 `ClockView`（`GraphicsMode.NATIVE`，像素级）来检查，见 [ClockViewRenderTest.java](app/src/test/java/com/andraid/colourclock/ClockViewRenderTest.java)，8 项全部通过（`gradle testDebugUnitTest`）：

- 竖屏 TIME 模式：颜色顶到屏幕四边四角（无页面色边框）、卡片色 = 当前时刻的十六进制色、大字读数不被裁切；
- 点 COLOUR 胶囊：模式切换、读数变为 `#rrggbb`，颜色仍顶到四边；
- 状态栏安全区 36dp 时：颜色依旧铺满四边，但表头随安全区下移——在旧表头位置点击不会打开面板，在新位置会（这条直接对应 Android 13 真机截图暴露的问题）；
- 点右上 ⓘ：卡片收成 80dp 横条、下方回到页面底色并绘出 about 文案、文案不越界；
- 横屏（宽布局，含表头横线）：读数仍在内容安全框内；
- 横屏 about：文案落在颜色让出的右侧条带里，且整块不超出面板底部；
- 中文正文断行：没有空格可断的中文按"避头点"规则换行——`，。、；：！？）》」』】〉…—` 等不会出现在行首，`（《「【〈` 等不会出现在行尾，且断行前后逐字符对账，一个字也不丢；
- 夜间：页面变黑、墨色跟随主题。

渲染出的 PNG 在 `app/build/render-check/`。

## 4.X 兼容性审计

`gradle lintDebug` 的 `NewApi` 检查现在为 **0 个 Error**（`InlinedApi` 还剩 3 条 Warning：`SYSTEM_UI_FLAG_LAYOUT_*` 是编译期内联的整型常量，在 API 14/15 上会被框架忽略，不会崩）。审计过程中修掉了三个真会在旧机器上炸的问题：

- `WindowInsets.getSystemWindowInsets()` 实际是 API 30 才有的方法，原来在 21–29 分支里被调用 → 改用 `getSystemWindowInsetLeft/Top/Right/Bottom`（API 20+）。
- `DisplayCutout` 是 API 28（Android 9）引入的，原来的 `>= 29` 判断会让刘海屏的 Android 9 设备拿不到安全区 → 改为 28，并把这段单独拆成 `@TargetApi(28)` 的 `Cutout` 内部类，只有真正执行到才会加载该类。
- 全屏 flag 走三代 API：API 30+ 用 `WindowInsetsController`，API 19–29 用 `SYSTEM_UI_FLAG_*` + `IMMERSIVE_STICKY`，API 14–18 只用旧 flag（`IMMERSIVE_STICKY` 本身是 API 19，已单独判断）。

`android:screenOrientation="user"` 在 Android 16 的大屏设备上可能被系统忽略（lint 的 `DiscouragedApi` 提示），这只会让它更自由地跟随旋转，与需求一致；界面本身对竖横两向都是自适应的。

## 与网站有意不同的地方

1. **字体**：原站用商业字体 Aeonik Mono（CoType），这里改用系统等宽字体 `Typeface.MONOSPACE`，about 面板里也如实写明这一点。
2. **胶囊文字**：网页上 TIME / COLOUR 的文字只在 hover 时出现，触屏没有 hover，所以这里常驻显示。
3. **对比度兜底**：原站在午夜前后会出现"白底白字"（时钟颜色用页面色绘制，而页面色恰好接近卡片色）。这里加了 WCAG 式的对比度检查（`contrast()` / `readable()`），低于 2.1 时改用另一极值或推亮/推暗，保证文字始终可读。
4. **交互**：点大字读数也能在 TIME / COLOUR 间切换（网页只有胶囊和键盘快捷键）。
5. **只有 24 小时制**：与原站一致，没有 12 小时制开关。
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

- `app/build/outputs/apk/debug/app-debug.apk`（94,303 B，已用 debug key 签名，可直接安装）
- `app/build/outputs/apk/release/app-release.apk`（66,301 B，R8 混淆 + 资源收缩，同样用 debug key 签名，便于本机验证；上架请换自己的签名）

安装到手机：

```bash
"/d/Qoder/toolchain/android-sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

`settings.gradle` 里把 `maven.aliyun.com` 排在 `google()` / `mavenCentral()` 之前，国内网络下载依赖会快很多；海外构建可以忽略。

debug 包比 release 包大 28 KB，并不是内容多了。把两个 APK 的 zip 结构逐字节拆开算（脚本按 central directory 的 local-header offset 复位每一段）：

| | debug | release |
| --- | --- | --- |
| 条目实际数据 | 70,312 B | 60,446 B |
| 条目之间的对齐填充 | 17,488 B | 0 B |
| APK Signing Block | 4,096 B | 4,096 B |
| Central directory + EOCD | 2,407 B | 1,759 B |
| 合计 = 文件大小 | **94,303 B** | **66,301 B** |
| 其中 `classes.dex` | 31,676 B（未混淆） | 22,452 B（R8） |

也就是说 debug 包里有 17 KB 不属于任何条目的空隙——AGP 的写入顺序加上页对齐留下的填充，最大一段 14,425 B 就空在 `AndroidManifest.xml` 前面，release 包则是 0。v1.0.0 的 debug 包是 76,361 B，这 18 KB 的跳动和这段空隙同量级，并没有对应任何内容变化（同期的 release 包只因为中文正文长了 416 B）。看体积请以 release 包为准。

## 版本

当前是 **1.0.1**（`versionCode 2`）。基线 **v1.0.0**（`versionCode 1`）是 2026-11-05 在 Android 13 真机上确认「颜色铺满到屏幕四边、系统栏隐藏」通过的那一版，已用 `git tag -a v1.0.0` 锚定。

后续按 <https://semver.org/> 递增 `versionName`：

| 位 | 什么时候加 | 本项目里的例子 |
| --- | --- | --- |
| MAJOR | 与旧版不兼容的改动（换取色算法的输入语义、改应用 ID、删掉已有交互） | `2.0.0` |
| MINOR | 向后兼容的新功能（12 小时制、常亮开关、主题） | `1.1.0` |
| PATCH | 只修 bug，不加功能（安全区算错、文案溢出、某个 API 等级崩溃） | `1.0.1` |

`versionCode` 与它无关，每发一版只 +1（Android 靠它判断能否覆盖安装），规则写在 [app/build.gradle](app/build.gradle) 的 `defaultConfig` 上方。

每次升版的动作固定三步：改 `versionName` / `versionCode` → 提交 → `git tag -a vX.Y.Z`。

1.0.1 的改动只有一处：ⓘ 面板正文 `about_body` 换成中文的离线与隐私声明（标题、字体说明、时区说明、署名保留）。顺带补上了中文断行的避头点规则和一条对应的渲染测试，属于文案与排版修复，没有新增用户可见的功能，所以按 PATCH 走。

## 体积与依赖

release 包的 `classes.dex` 里只有 8 个类（`MainActivity` + 7 个混淆后的类），`dexdump` 确认没有任何 `androidx.` / `kotlin.` / `android.webkit.` 引用；`aapt2 dump badging` 确认 `minSdkVersion:'14'`、`targetSdkVersion:'36'` 且没有任何 `uses-permission`。`gradle.properties` 中的 `android.useAndroidX=true` 只是为了让 Robolectric 的测试夹具（它自己依赖 `androidx.test:monitor`）能跑起来，不会把 AndroidX 带进 APK；`app/src/test/AndroidManifest.xml` 里的 `tools:overrideLibrary` 同理，只作用于单测的清单合并。
