# Gemini 2 深度预览与站位

0.6.1 支持显式选择「Gemini 2 深度预览」。Android 用 Orbbec SDK v2.0.6 同时采集 RGB 与 Y16，启用帧同步，通过 `AlignFilter(StreamType.COLOR)` 将深度配准到彩色画面。App 丢弃尺寸、格式或时间差不合要求的帧。已有 RGB 摄像头入口可单独使用。

ML Kit 为这一帧输出人体关键点和前景置信度。深度转成毫米后，用躯干内侧的多个有效采样点估计距离。前景分割可过滤背景，离散过大的深度会被拒绝。有效深度交给已有的遮挡绘制。已有的长袖胸前遮挡关闭规则保持有效。

「站位标定」保存现场测得的最近和最远距离。只有当前帧具备可用的对齐深度和人体距离时，才会提示向前或向后。清除标定后回到入镜和居中提示。停止预览、退出页面和断流时释放相机和临时帧。

## 编译和验证

从 [Orbbec 官方 Android Wrapper v2.0.6](https://github.com/orbbec/OrbbecSDK-Android-Wrapper/tree/v2.0.6) 取得 `app/libs/obsensor_v2.0.6_2026031801_release.aar`。Gradle 核验 SHA-256 并将 SDK 放入指定构建。常规构建无需 AAR，保持原 RGB／合成入口。带 SDK 的测试包要求 Android API 24 或以上。

```powershell
.\gradlew.bat "-PorbbecSdkAar=C:/path/obsensor_v2.0.6_2026031801_release.aar" :app:assembleDebug :app:assembleDebugAndroidTest :core-vision:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
```

带 SDK 的构建、单元测试和 Lint 通过。单元检查覆盖深度比例、旋转、无效值、分割掩码过滤和距离提示。Android 15 模拟器通过 5 项原生测试，包含 `RgbdReplayTest` 的端侧姿态与分割、合成 1.90 米深度与前进／后退提示、无 Gemini 2 时的清晰报错，以及已有 RGB 相机通道的三个回归用例。回放可通过 `rgbd_cg_fixture` 读取调试 App 私有 files 目录中的获准 CG 图。

## 待现场确认

1. 在镜子装入带 SDK 的测试包，关闭可能占用相机的厂家 Demo。开启「Gemini 2 深度预览」，完成 USB 授权，核对彩色画面、对齐深度和人体分割状态。
2. 用现场测量确认 RGB 与深度边缘对齐及实际距离，标定有效站位范围。分别走近、退远和回到适中位置，观察提示。
3. 测短袖前臂遮挡、预览帧率、USB 拔插重连和退出清理。模拟帧仅用于检查软件流程。设备方向、取流稳定性和帧率需以镜子上的结果为准。

[ML Kit 人体分割说明](https://developers.google.com/ml-kit/vision/selfie-segmentation/android)
