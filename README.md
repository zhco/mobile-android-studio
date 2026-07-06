---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 6bf887ea0b54f9e8c4a80e98ead3aa40_fd5cd863787611f1a7da5254006c9bbf
    ReservedCode1: sL+nM6/1lUiboFpCwAbwPp5t+f37H5odrDHiW+wCI9r3SAby8fuxNcJ1GHQCjPrY+sj8CcYL2b1bC/8LRIFbRSk8bEduPMfoqGsEMiJAo5oNO2Dv2eGxbWYi3w198zxAnZ5nBO7rZ3kl/Y5tkWjgi+NcZu8NbrqrPN80gnyF4yZFmp55ciF1lJSS+sw=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 6bf887ea0b54f9e8c4a80e98ead3aa40_fd5cd863787611f1a7da5254006c9bbf
    ReservedCode2: sL+nM6/1lUiboFpCwAbwPp5t+f37H5odrDHiW+wCI9r3SAby8fuxNcJ1GHQCjPrY+sj8CcYL2b1bC/8LRIFbRSk8bEduPMfoqGsEMiJAo5oNO2Dv2eGxbWYi3w198zxAnZ5nBO7rZ3kl/Y5tkWjgi+NcZu8NbrqrPN80gnyF4yZFmp55ciF1lJSS+sw=
---

# Mobile Android Studio (MAS)

在手机上编写、编译、安装 Android 应用的完整 IDE。

## 架构

```
APK
├── WebView ──加载──→ code-server (本地 HTTP :18080)
│                     ├── Kotlin Language Server (补全/诊断)
│                     ├── Java LSP (Eclipse JDT)
│                     └── XML Assist
├── BuildManager ────→ ./gradlew assembleDebug
│                     ├── aapt2 (ARM64, bundles in assets)
│                     ├── d8/r8 (ARM64)
│                     └── apksigner (ARM64)
└── ForegroundService → 保活 code-server 进程
```

## 项目结构

```
mobile-android-studio/
├── app/
│   ├── build.gradle.kts          # App 构建配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── code-server/       # ← 需要下载 code-server ARM64
│       │   └── android-sdk/       # ← 需要下载 SDK 工具链
│       ├── java/com/marvis/mas/
│       │   ├── MASApplication.kt          # Application 入口
│       │   ├── server/
│       │   │   ├── CodeServerManager.kt    # code-server 进程管理
│       │   │   └── CodeServerService.kt    # 前台保活服务
│       │   ├── build/
│       │   │   └── BuildManager.kt         # Gradle 构建 + APK 安装
│       │   └── ui/
│       │       └── MainActivity.kt         # WebView 容器
│       └── res/
│           ├── layout/activity_main.xml
│           └── xml/file_paths.xml
├── prepare-assets.sh             # 自动下载并准备 assets
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 构建步骤

### 1. 准备依赖资源

在 Linux 电脑上运行（需要 curl 和网络）：

```bash
chmod +x prepare-assets.sh
./prepare-assets.sh
```

这会自动下载：
- code-server 4.106.2 (ARM64, ~85MB 压缩)
- Android SDK Build Tools 34.0.0
- Android Platform 34 (android.jar)

放在 `app/src/main/assets/` 下。

### 2. 构建 APK

用 Android Studio 打开本项目，然后：

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

或者命令行：

```bash
./gradlew assembleDebug
```

### 3. 安装到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## APK 体积

| 组件 | 大小 |
|------|------|
| code-server ARM64 | ~85MB |
| Android SDK (裁剪) | ~30MB |
| Gradle Wrapper | ~4MB |
| App 代码 + WebView | ~20MB |
| **总计 (压缩)** | **~160MB** |
| **安装后占用** | **~350MB** |

## 运行时行为

- **首次启动**：解压 assets 到私有目录（~30s），启动 code-server
- **构建项目**：首次需要联网下载 Gradle 依赖（和桌面 AS 一致）
- **编码编辑**：完全离线，LSP 本地运行

## 当前状态

- [x] 项目骨架
- [x] code-server 进程管理
- [x] Gradle 构建桥接
- [x] 前台保活服务
- [ ] code-server assets 下载 (运行 prepare-assets.sh)
- [ ] ARM64 build-tools 替换 (aapt2/d8/apksigner 需 ARM64 版本)
- [ ] Kotlin LSP 扩展预装
- [ ] 项目模板
- [ ] 构建输出面板 UI

## 关键待解决问题

### build-tools ARM64 二进制

Google 官方 build-tools 只提供 x86_64。ARM64 版本需要从以下途径获取：

1. **Termux 包管理器**（推荐）
   ```bash
   # 在有 Termux 的手机上：
   pkg install aapt2 dx ecj
   # 然后从 $PREFIX/bin/ 提取二进制文件
   ```

2. **AOSP 源码交叉编译**
   ```bash
   # 在 AOSP 源码树中：
   source build/envsetup.sh
   lunch aosp_arm64-eng
   make aapt2 d8 apksigner zipalign
   ```

3. **从 CodeAssist 提取**（已包含 ARM64 aapt2）

提取后的二进制文件放入：
```
app/src/main/assets/android-sdk/build-tools/34.0.0/
├── aapt2         (ARM64)
├── d8            (jar, 架构无关)
├── apksigner     (jar, 架构无关)
├── zipalign      (ARM64)
└── ...
```

### Gradle 构建环境

手机上运行 Gradle 需要 JDK。方案：

- 内嵌 ARM64 OpenJDK 到 assets/jdk/（~150MB）
- 或要求用户安装 Termux 并 `pkg install openjdk-17`

当前代码假设 JDK 在 `filesDir/jdk/`。

## 许可证

本项目代码采用 Apache 2.0 许可。

code-server 采用 MIT 许可。
Android SDK 工具采用 Apache 2.0 许可。
*（内容由AI生成，仅供参考）*
