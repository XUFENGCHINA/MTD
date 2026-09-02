# 多线程下载器 (MultiThreadDownloader)

> 一个纯 Java 实现的 Android 多线程分段下载器，**零第三方依赖**，仅需 `INTERNET` 权限。

![Platform](https://img.shields.io/badge/Android-6.0%2B-brightgreen)
![API](https://img.shields.io/badge/API-23--36-blue)
![Language](https://img.shields.io/badge/Java-100%25-orange)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

## ✨ 功能特性

| 特性 | 说明 |
|---|---|
| 🚀 多线程分段下载 | 将文件切分为 N 段并行下载，**1~16 线程**可调 |
| 🧠 智能降级 | 服务器不支持 `Range` 或大小未知时，自动退化为单线程流式下载 |
| 🔁 断点重试 | 每一段失败自动重试 **3 次** |
| 📊 实时进度 | 百分比、已下载/总大小、**瞬时速度**（B/s、KB/s、MB/s） |
| ⏹ 手动停止 | 随时中断下载任务 |
| 🔒 最小权限 | 仅 `INTERNET`，下载到应用专属目录，**无需任何存储授权** |
| 📱 Android 16 适配 | `targetSdk 36`，完整 edge-to-edge 沉浸式布局 |

## 📸 界面示意

```
┌─────────────────────────────────┐
│  多线程下载器                     │  ← 深蓝沉浸式顶栏
│  多线程分段 · 高速拉取 · 断点重试  │
├─────────────────────────────────┤
│  下载链接                         │
│  [ https://example.com/1.zip  ] │
│                                  │
│  下载线程数    4线程  ────●───     │  ← 滑块 1~16
│  保存文件名（可选，留空自动提取）   │
│  [ 1.zip                        ]│
│  保存位置                         │
│  /storage/emulated/0/.../files/… │
├─────────────────────────────────┤
│  下载进度                         │
│  ▓▓▓▓▓▓▓▓░░░░░░░░  42%          │
│  42% · 8.4MB/20MB · 1.25 MB/s   │
│                                  │
│  运行日志                         │
│  > 文件大小 20.00 MB · 8线程分段   │
│  > 下载完成 / 文件: /storage/...  │
├─────────────────────────────────┤
│  [ 开始下载 ] [ 停止 ] [ 复制路径 ]│
└─────────────────────────────────┘
```

## 📦 下载

- **最新 APK**：见本仓库 [`apk/`](apk/) 目录，或前往仓库「Releases」页面下载
- 包名：`com.mtdownloader`
- 支持系统：Android 6.0（API 23）至 Android 16（API 36）

> 💡 首次安装若提示「未知来源」，请在系统设置中允许安装未知应用即可（仅需网络权限，无风险）。

## 🏗️ 技术架构

整个项目**不依赖任何第三方库**，纯手写 Java，脱离 Android SDK 也能用 `javac/java` 直接做单元测试。

### 核心引擎 `MultiThreadDownloader.java`

```
┌────────────────────────────────────────────┐
│ start()                                    │
│   └─ probe()  Range:bytes=0-0 探测          │
│       ├─ 206 → 支持分段 + 解析 Content-Range │
│       └─ 200 → 不支持分段 / 大小未知         │
│   └─ 分支                                   │
│       ├─ 分段: N 线程 × 独立 RandomAccessFile │
│       └─ 流式: 单线程 RandomAccessFile/FOS   │
│   └─ doneBytes 原子计数 → 400ms 节流回调      │
└────────────────────────────────────────────┘
```

关键设计点：

- **分段并行安全**：每个线程用**独立的 `RandomAccessFile`** 写入互不重叠的偏移区间，天然线程安全；
- **失败重试**：每一段独立重试，互不影响；
- **进度合并**：使用 `AtomicLong` 计数已下载字节，`maybeNotify()` 每 400ms 节流回调，避免频繁 UI 刷新；
- **零权限下载**：保存到 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`（应用专属目录），无需 `WRITE_EXTERNAL_STORAGE`。

### 界面 `MainActivity.java`

纯代码构建 UI（无任何 XML 布局），包括 URL 输入、线程数滑块、文件名、进度条、日志区、开始/停止/复制路径三个按钮。

### edge-to-edge 适配

由于 `targetSdk ≥ 35` 后系统强制 edge-to-edge（`statusBarColor` 等被忽略），项目通过 `setOnApplyWindowInsetsListener` 手动处理系统栏 insets，让顶栏保持沉浸式深蓝、底部按钮栏避开导航栏。

## 📁 目录结构

```
MTDownloader/
├── AndroidManifest.xml              # 清单（仅 INTERNET 权限）
├── build_apk.sh                     # 一键构建脚本
├── res/                             # 资源（图标/颜色/主题/字符串）
│   ├── drawable/                    # 自适应图标前后景
│   ├── mipmap/                      # 启动图标（含低版本回退）
│   └── values/                      # colors / strings / styles
├── src/com/mtdownloader/
│   ├── MainActivity.java            # 界面（纯代码构建）
│   └── MultiThreadDownloader.java   # 核心下载引擎
├── test/
│   └── TestDownloader.java          # JVM 单元测试
└── apk/
    └── 多线程下载器.apk              # 成品
```

## 🔨 构建

### 环境要求

- JDK 8+
- Android SDK Build-Tools（`aapt2`、`dx`、`zipalign`）
- Android SDK Platform `android-34`（`android.jar`）
- apksigner（AOSP 版，支持 V2/V3）
- Python 3（打包用）
- 一个签名用 keystore（`debug.keystore`）

### 一键构建

```bash
bash build_apk.sh
```

脚本流程：`aapt2 compile/link → javac → dx → 合并 classes.dex → zipalign → apksigner(V1+V2+V3)`。

> ⚠️ **注意**：早期 `dx` 不支持 Java 8 方法引用/lambda（报 `Unable to find method metafactory`），本项目已全部改用匿名内部类，构建稳定。

## 🧪 测试

`test/TestDownloader.java` 使用本地 `HttpServer`（`com.sun.net.httpserver`）模拟**支持 Range / 不支持 Range / chunked 未知大小 / 慢速** 四种服务器场景，配合 MD5 校验下载内容一致性。

```bash
cd test
javac --add-modules jdk.httpserver -encoding UTF-8 \
  -d out ../src/com/mtdownloader/MultiThreadDownloader.java TestDownloader.java
java --add-modules jdk.httpserver -cp out TestDownloader
```

| 用例 | 场景 | 结果 |
|---|---|---|
| 1 | 多线程分段（2MB / 8线程） | ✅ |
| 2 | 单线程降级（不支持 Range） | ✅ |
| 3 | 未知大小流式（chunked） | ✅ |
| 4 | 大数据（16MB / 32线程） | ✅ |
| 5 | 停止功能（慢速源） | ✅ |

## ⚠️ 说明

- 下载文件默认保存在**应用专属下载目录**（`Android/data/com.mtdownloader/files/Download/`），卸载应用会一并清除；可通过「复制路径」按钮拿到完整路径后用文件管理器查看。
- 请遵守目标服务器条款，仅用于下载你有权获取的资源。

## 📄 License

本项目基于 GPL3.0 开源，遵守即可正常修改和分发。
