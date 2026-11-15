# Moda for Ray-Ban Meta Gen 2

Moda 是一款面向 Ray-Ban Meta Gen 2 的 Android 专注助手。它只做好一件事：在不依赖云账户或 API Key 的前提下，提供更克制、更可靠的专注体验。

## 功能

- 通过 Meta Wearables Device Access Toolkit（DAT）1.0.1 连接眼镜。
- 专注计时、休息确认、可选的舒缓音乐、本地统计和勿扰模式。
- 通过眼镜摄像头提供低频、温和的手机分心和观看距离提醒。
- 优先使用眼镜 IMU 姿态数据；不可用时回退到手机传感器。低头提醒默认关闭。
- 支持手机端离线英文语音控制；眼镜触控和语音作为实验性补充。
- 支持 MockDeviceKit 测试模式，用手机后摄模拟眼镜。

## 产品边界

项目已移除 Cooking、Meeting、日历、云端 AI 和多模式导航。这些功能的可靠性不足以成为核心体验的一部分。专注设置和统计仅保存在本机。

眼镜触控与语音属于 DAT 实验性能力，是否可用取决于设备和开发者权限；界面与手机麦克风控制始终是完整的后备方案。

## 构建

要求：JDK 17、Android SDK 36、Android Studio Narwhal 或更新版本。

```powershell
.\gradlew.bat assembleDebug --console=plain
```

首次构建会下载约 40 MB 的离线 Vosk 英文语音模型到已忽略的 `.cache/` 目录。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用眼镜

1. 在 Meta AI app 配对兼容的 Ray-Ban Meta Gen 2，并打开 Developer Mode。
2. 在 Moda 中注册 Meta AI、连接眼镜并授权眼镜摄像头。
3. 开始专注会话。可说 “start focus”、“pause”、“resume”、“end focus”、“yes” 或 “not yet”，也可使用界面按钮。

没有眼镜时，开启 Test mode 即可用手机后摄开发和演示；这只能验证应用流程，不能代替真机眼镜验证。

## 代码结构

| 路径 | 作用 |
|---|---|
| `glasses/GlassesLink.kt` | DAT 注册、会话、摄像头、IMU、MockDeviceKit 和实验性控制 |
| `focus/FocusEngine.kt` | 计时、休息确认、提醒状态与会话生命周期 |
| `vision/PhoneDetector.kt` | 本地手机、屏幕和书本检测 |
| `audio/VoiceCommands.kt` | 离线固定短语语音控制 |
| `system/FocusService.kt` | 前台会话与手机解锁信号 |
| `data/Stores.kt` | 本地设置和每日统计 |

## 隐私与安全

摄像头画面仅在本机处理，用于专注体验。Moda 不是姿态、视力或安全系统；所有提醒都是可选提示，不构成医疗建议。
