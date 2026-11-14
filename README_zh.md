# FocusGlasses 中文说明

Junction 2026 · 基于 Meta Wearables Device Access Toolkit（DAT）1.0.1 的 Ray-Ban Meta Gen 2 眼镜助手。App 运行在手机上，通过眼镜提供摄像头、IMU、触控和实验性语音能力。

[English README](README.md)

## 当前功能

| 模块 | 已实现能力 |
|---|---|
| 眼镜连接 | DAT 注册、摄像头流、IMU、MockDeviceKit 测试模式、实验性触控和 Speech 接入 |
| 模式调度 | Work、Cooking、Meeting、Study、Relax 的开启、暂停、恢复、结束和切换 |
| Work Mode | 专注计时、舒缓音乐、勿扰模式、手机分心提醒、距离/坐姿提醒、休息确认、专注树和本地统计 |
| Cooking Mode | 手动冰箱情境提示、单帧 Gemini 建议、10 分钟计时、饭后洗碗本地提醒 |
| Meeting Mode | 读取手机本地日历下一场会议、开始/延迟/跳过确认、录音同意确认和本地行动项 |

## Demo 边界

- Study 和 Relax 目前只有生命周期外壳，尚未接入各自的内部功能。
- Cooking Mode 不会自动识别冰箱、火焰、烧焦或食品安全状态；冰箱触发为手动操作。Gemini 仅在用户主动点击后分析一张画面，且需要配置 API key。
- Meeting Mode 会记录用户是否同意录音，并保存本地笔记；尚未实现持续录音或会议转写，也不是后台全天候日历闹钟。
- 眼镜触控和 Speech 属于 DAT 实验能力，需要开发者权限与真实设备验证。手机控件和 MockDeviceKit 仅用于验证 App 流程。

## 后续计划

- 为 Study 和 Relax 接入真实功能引擎。
- 在明确获得全体参会者同意后，加入会议音频采集和持续转写。
- 加入可靠的后台日历提醒与确认后的会后任务。
- 改进 Cooking Mode 的可选视觉辅助，但不将其作为安全系统。

## 运行要求

- Android 12+，JDK 17，Android SDK 36。
- 真实眼镜测试需 Meta AI App、已配对的兼容眼镜和 Developer Mode。
- 没有眼镜时，可以用 App 的 Test mode 通过手机后摄模拟眼镜摄像头。

## 代码地图

| 路径 | 说明 |
|---|---|
| `glasses/GlassesLink.kt` | DAT 注册、会话、摄像头流、IMU、MockDeviceKit、触控和 Speech |
| `modes/ModeController.kt` | 所有 mode 的生命周期和切换边界 |
| `focus/FocusEngine.kt` | Work Mode 引擎 |
| `cooking/CookingEngine.kt` | Cooking Mode 建议、计时和洗碗提醒 |
| `meeting/MeetingEngine.kt` | 本地日历会议确认、同意和行动项 |
| `audio/VoiceCommands.kt` | 手机离线语音命令 |
| `ai/GeminiClient.kt` | 可选 Gemini 图像建议与会议文本意图分类 |
