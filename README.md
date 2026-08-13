# 📖 EPUB 阅读器（安卓 App）

把网页版 EPUB 阅读器打包成安卓原生 App，在手机上沉浸式阅读。

> 📄 完整的**功能设计文档**见 [docs/功能设计.md](docs/功能设计.md)。

## 功能

- **书架**：导入 EPUB 后自动保存，显示封面/标题/作者/上次进度，卡片右上角 ✕ 删除
- **沉浸式阅读**：竖屏单页、自动隐藏工具栏/底栏（点屏幕中间唤出）；横屏（平板）自动双页并排、工具栏常显（可点「🌊 沉浸」进入沉浸）；旋转时自动切换布局与沉浸状态（手动选过布局则优先）；打开书时目录默认关闭
- **翻页**：左右滑动 / 点击左右 1/3 屏幕 / 音量键（固定开启）
- **进度恢复**：基于 CFI 精确回到上次阅读的页面
- **手机模式工具栏**：竖屏时顶部按钮收进 ☰ 下拉菜单（字号、背景色、主题、单双页、沉浸、目录、书架）；横屏/平板仍用完整工具栏
- **阅读设置**：字号、主题（深色/浅色）、背景色（白色/浅绿/类纸/🎨自定义颜色）、单双页布局、目录

## 构建

**方式一：命令行（推荐，最稳定）**

```bash
cd <项目根目录>
set JAVA_HOME=<JDK 17+ 安装目录>
gradlew.bat assembleDebug
# 产物: app\build\outputs\apk\debug\app-debug.apk
```

（本机系统 Java 是 1.8，必须用 JDK 17+。`JAVA_HOME` 指向本机 JDK 17+ 安装目录即可，可使用 Android Studio 内置的 Embedded JDK）

**方式二：Android Studio**
1. 打开本项目目录 → 自动 Sync（首次下载 Gradle 8.9 + 依赖）
2. 连接手机 / 模拟器 → Run ▶

## 故障排查

- **Sync 报 `Plugin was not found`**：关闭重开 Studio → `File → Sync Project with Gradle Files`；还不行就 `File → Invalidate Caches / Restart`，或删 `%USERPROFILE%\.gradle\caches` 后重试。
- **提示 JDK 版本过低**：Gradle 8.9 需要 JDK 17+。命令行构建把 `JAVA_HOME` 指向 JDK 17。
- **模拟器窗口黑屏但 App 正常**：Windows 上模拟器 GPU 渲染偶发失效，用软件渲染重启模拟器：`emulator -avd <名称> -gpu swiftshader_indirect`。

## 验证清单

| 项目 | 预期 |
|---|---|
| 导入 | 点"📂 打开新EPUB文件"→ 系统文件选择器 → 选书 → 书架出卡片 |
| 打开 | 点卡片进入阅读器，沉浸模式（无系统栏） |
| 翻页 | 左右滑动、点左/右 1/3、音量键 三种方式均翻页 |
| 进度 | 翻几页 → 返回书架 → 重开 → 回到原页 |
| 删除 | 书架卡片 ✕ → 原生确认框 → 确认删除 |
| 返回键 | 阅读器内按返回 → 回书架；书架按返回 → 退出 |
| 大文件 | 导入 30–50MB EPUB 无崩溃 |

## 架构

```
app/src/main/
├─ assets/
│  ├─ reader-bookshelf.html   # 阅读器本体（安卓适配副本，桌面浏览器也可直接打开）
│  └─ lib/{epub.js, jszip.min.js}
└─ java/com/example/epubreader/
   ├─ MainActivity.kt         # 入口：WebView、沉浸式、手势、音量键、返回键
   ├─ EpubStore.kt            # 书籍内部存储（filesDir/epubs，流式导入）
   ├─ EpubBridge.kt           # JS 桥 window.AndroidBridge（导入/列表/删除/状态）
   ├─ EpubWebViewClient.kt    # WebViewAssetLoader 同源加载页面与书籍
   ├─ EpubWebChromeClient.kt  # confirm()/alert() 原生弹窗
   ├─ ImmersiveController.kt  # 全屏/系统栏控制
   └─ GestureController.kt    # 滑动/点击翻页、点中间切换系统栏
```

关键设计：

- **书籍不走 WebView 的 IndexedDB**（有跨会话丢失与容量限制风险）。书籍文件存 App 私有目录，WebView 通过 `WebViewAssetLoader` 以同源 `https://appassets.androidplatform.net` 流式读取，不整包进内存。
- **HTML 适配最小侵入**：检测到 `window.AndroidBridge` 时切换数据源（`IS_NATIVE` 分支），桌面浏览器打开原文件功能不变。

## 维护：网页更新后如何同步到 App

App 里的阅读器是**打包快照**，改网页后 App 不会自动更新，需要重新构建安装。

**以后改阅读器功能，直接改这个文件：**
```
app/src/main/assets/reader-bookshelf.html
```
（它带安卓适配层，同时兼容桌面浏览器——改完可以用浏览器打开验证，再重建 App 即可。）

改动后重建安装：
```bash
set JAVA_HOME=<JDK 17+ 安装目录>
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 调试

手机连接电脑后，Chrome 打开 `chrome://inspect` 可查看 WebView 的 DOM/控制台/网络。

## 许可证

本项目采用 [MIT License](LICENSE)。

所包含的第三方库（epub.js、JSZip 等）的版权声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
