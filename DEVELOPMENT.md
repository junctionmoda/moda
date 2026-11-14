# 开发环境与工作流

给一起开发的人和你的 AI 编程助手看的。目标：拉下代码 → 改 → 在真机上验证 → push，每一步都有可直接复制的命令。

## 1. 主开发机的环境（照着装就能编译）

| 项目 | 版本 / 位置 |
|---|---|
| 系统 | Windows 11，终端是 **PowerShell 5.1**（也可用 Git Bash） |
| JDK | Microsoft OpenJDK **17**（`C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`） |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`，platform **android-36**，build-tools 35/36，platform-tools（adb） |
| Gradle | wrapper 自带 8.14.1，AGP 8.11.1，Kotlin 2.2.21，Compose |
| 依赖 | 全部来自 Google Maven / Maven Central，**不需要任何 token**（Meta DAT 1.0.1、MediaPipe 0.10.26、Vosk 0.3.75） |
| 测试手机 | 小米 24031PN0DC，Android 16，arm64，已装 Meta AI app（`com.facebook.stella`） |
| 眼镜 | Ray-Ban Meta Gen 2，在 Meta AI app 里开了 Developer Mode |

装了 Android Studio 的话，JDK 和 SDK 它都会带；`local.properties`（`sdk.dir=...`）Android Studio 会自动生成，**不要提交**。

## 2. 编译、安装、看日志（PowerShell）

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"; $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug --console=plain
```

- 第一次编译会自动下载 Vosk 语音模型（约 40 MB）到 `.cache/`，之后不再下载。
- 产物：`app\build\outputs\apk\debug\app-debug.apk`（约 130 MB）

安装到手机（手机开 USB 调试并用线连上）：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

看日志：所有 app 日志的 tag 都以 `FocusGlasses:` 开头。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -v time | Select-String -SimpleMatch "FocusGlasses"
```

| Tag | 内容 |
|---|---|
| `FocusGlasses:Engine` | 每帧检测结果 `det phone=… screen=… book=…`、`dist …`、`distraction:`、`too close:`、`posture alert:`、`voice command …`、`check-in: …` |
| `FocusGlasses:Commands` | 语音识别：`heard "…" -> START_WORK` |
| `FocusGlasses:Voice` | TTS 引擎和当前音色 |
| `FocusGlasses:Link` | 眼镜连接、注册、`imu x y z` |
| `FocusGlasses:Gemini` | Gemini 调用失败时的 HTTP 错误 |

**PowerShell 5.1 的坑：**
- 没有 `&&`，用 `;`。
- 运行带路径的 exe 要在前面加 `&`。
- `curl` 是别名，真 curl 写 `curl.exe`。
- `logcat -s` 遇到带冒号的 tag 会失效，用上面的 `Select-String`。
- **不要用 `Get-Content` / `Set-Content` 改源码**，它会弄坏 UTF-8（Kotlin 文件里有 `·`、`°`、`…`）。用编辑器，或用 Python 脚本。

## 3. 没有眼镜也能开发

- **设置 → Test mode**：MockDeviceKit 用手机后摄像头模拟眼镜画面，手机检测、距离检测都能测。
- **Demo mode**：时钟快 20 倍，40 分钟专注只要 2 分钟，延长 10 分钟只要 30 秒，提醒也更快触发。拍视频和调试都用它。
- MediaPipe 只有 arm64 原生库：x86 模拟器能装、能跑界面，但跑不了检测。用真机测。

## 4. 用真眼镜

1. Meta AI app ≥ V292，眼镜固件 ≥ V128。
2. Meta AI → Settings → App Info → 连点版本号 5 次 → 打开 **Developer Mode**。manifest 里的 `mwdat_application_id` / `mwdat_client_token` 用占位符 `0` 就够；日志里出现 `Attestation failed: TOKEN_NOT_CONFIGURED` 不影响使用。
3. 在 app 里依次点：Register in Meta AI → Connect glasses → 授权眼镜摄像头。
4. **必须戴在脸上、镜腿打开**。否则会报 `START_ERROR_DEVICE_UNAVAILABLE`（眼镜报告 DOFFED，即未佩戴）。

完整的体验流程（说什么、会听到什么）见 README 的 Work Mode 一节。

## 5. 代码结构

见 [README.md](README.md) 的 Code map。核心是 `focus/FocusEngine.kt`：它是一个状态机（IDLE → FOCUS → CHECK_IN → BREAK），所有状态变化都在主线程上，界面通过 `FocusUi` 这个 StateFlow 读取状态。

改之前要知道的真机实测结论（数字都来自 logcat）：
- **IMU 轴**：头部俯仰在 X–Z 平面，前方是 +Z，所以 `GlassesLink` 里把 z 取反后再算角度。平视约 -14°，低头看桌面约 -50°。
- **距离检测**（`DistanceTracker`）：14 寸笔记本正常坐姿下检测框大小约 0.52–0.68，凑近时 0.78–1.0。凑近后检测经常丢帧或只框到一部分，所以用 6 秒窗口投票，大小 ≥ 0.78 直接算太近。
- **拿起手机**：手机举到眼前时，常被识别成占满画面的 "laptop"，再加上低头看手机，就会误报"太近"和"低头"。所以只要有手机迹象（分数 ≥ 0.25）或手机刚解锁，10 秒内不发距离和坐姿提醒。
- **低头提醒默认关**：低头的正当理由太多（记笔记、看键盘、看书），设置里保留了开关。
- **语音指令用手机麦克风**：一打开眼镜麦克风，蓝牙会切到通话模式，眼镜里的音乐会变成电话音质。识别器只认固定短语，要加新指令就改 `VoiceCommands.PHRASES` 和 `parse()`。
- **TTS**：建议在手机上把 Speech Services by Google 设为默认引擎，联网音色更自然。小米自带引擎读英文很生硬。
- **Gemini**：`gemini-2.5-flash` 已不对新用户开放（HTTP 404），默认模型改成了 `gemini-3.8-flash`。Key 只存在手机的 SharedPreferences 里，**永远不要写进代码**。

## 6. Git 工作流

两个人都直接在 `main` 上开发，所以每次 push 前先把对方的改动拉下来：

```powershell
git pull --rebase origin main
.\gradlew.bat assembleDebug --console=plain
git add -A
git commit -m "简短说明改了什么"
git push origin main
```

- **push 前必须能编译通过**。改了行为的话，在真机上跑一遍，并看一眼 logcat。
- 不要提交：`local.properties`、`.cache/`、`build/`、`*.apk`、任何 API key（这些 `.gitignore` 里已经排除了）。
- 两个人尽量别同时改同一个大文件（`MainActivity.kt`、`FocusEngine.kt`）。真冲突了，`git pull --rebase` 会停下来让你逐个解决。
