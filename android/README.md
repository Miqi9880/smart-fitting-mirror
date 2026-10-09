# Android 人体视觉开发版

本工程实现 T01 的模块骨架与公共类型，以及 T02 的普通 RGB 摄像头适配。默认打开明确标记的合成样例，点击“RGB 摄像头”后请求权限并运行本地姿态检测。没有试衣渲染、网络请求或云端生成。

[本次验证记录与截图](../docs/android-validation.md)

[无设备联调场景与四人接入验收](../docs/no-device-integration.md)

## 固定工具版本

| 项目 | 版本 |
| --- | --- |
| Gradle Wrapper | 8.11.1（已配置发行包 SHA256） |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.1.20 |
| 构建 JDK | 17 或 21；本次使用 21.0.10，字节码目标 17 |
| Android compileSdk / targetSdk | 35 / 35 |
| minSdk | 23（Android 6.0），企业设备需复核 |
| CameraX | 1.4.2 |
| ML Kit Pose Detection | 18.0.0-beta5，基础模型，STREAM_MODE |
| kotlinx.serialization | 1.8.0 |

固定上述组合用于首轮构建，未追求所有依赖的最新版本。ML Kit 姿态 API 仍为 Beta，后续升级须跑回归。依据：[AGP 8.9 兼容表](https://developer.android.com/build/releases/agp-8-9-0-release-notes)、[CameraX](https://developer.android.com/jetpack/androidx/releases/camera)、[ML Kit Android 姿态指南](https://developers.google.com/ml-kit/vision/pose-detection/android)。模型随依赖打包，本项目不把摄像头帧上传服务端。

## 第一次构建

1. 用 Android Studio 打开仓库的 android 目录，选择 JDK 17 或 21。
2. 在 SDK Manager 安装 Android SDK Platform 35、Build Tools 35.0.0 和 Platform Tools。
3. 让 Android Studio 生成 local.properties，或设置 ANDROID_HOME 到自己的 SDK。不要提交本机 SDK 路径。
4. Windows PowerShell 在 android 目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :core-contracts:test :core-vision:testDebugUnitTest
.\gradlew.bat :app:lintDebug :core-vision:lintDebug
```

macOS/Linux 使用 `./gradlew`。APK 位于 app/build/outputs/apk/debug/app-debug.apk。

仅当当前网络访问 Maven Central 返回 403 时，可选择 Google 提供的 Central 镜像；默认仓库保持官方 Central：

```powershell
.\gradlew.bat -PcentralRepositoryUrl=https://maven-central.storage-download.googleapis.com/maven2 :app:assembleDebug
```

不要关闭 TLS 校验或把密码写进仓库 URL。此配置只替换 Maven Central，不替换 Google Maven。

## 安装和使用

连接开启 USB 调试的 Android 6.0+ 手机，或启动 API 35 模拟器：

```powershell
adb devices
.\gradlew.bat :app:installDebug
adb shell am start -n com.smartfitting.mirror/.MainActivity
```

- 合成样例：不需要摄像头；选择静止、平移、大小变化、缺少左肩、丢失或恢复场景。可暂停、单步、从头重放，并选择 480×640 / 640×480 图像画幅。默认综合演示每 80 个样本短暂丢失。
- RGB 摄像头：优先前置，缺少前置时回退后置；只支持被 CameraX/Camera2 暴露的 RGB 设备，不保证厂商 USB 深度摄像机可直接使用。
- 镜面开关：只影响显示；内部图像与关键点始终未镜像。
- 查看原始帧：仅在内存展示当前帧，无关键点/UI 烧录，不写入相册；关闭弹窗或离开页面释放图像。
- 停止并清理：停止数据源，释放帧，禁止拍照。回到应用时只恢复用户此前仍在运行的数据源，并新建本地会话。
- 权限设置：权限被永久拒绝时打开应用设置。没有权限也能使用合成样例。

诊断页 FPS 是“推理完成后的输出频率”，不是摄像头采样 FPS。合成数据的 FPS 不能代表算法性能。深度、人体 mask、站位测距与遮挡当前均未实现，字段保持 null/unknown，能力为 false。

## 模块边界

| 模块 | 状态与接入点 |
| --- | --- |
| core-contracts | 纯 JVM Kotlin；对应 13 份公共 JSON 样例的 DTO、帧引用管理、接口与 FIT_CENTER 变换 |
| core-vision | CameraRgbSource、SyntheticVisionSource、BitmapPixels、PoseMapper |
| core-network | 可构建空模块，haiyangniu191-design 实现 FittingApi |
| feature-tryon | 可构建空模块，lyf-bee 维护 TryOnRenderer；产品页面属 app |
| app | 人体视觉诊断入口，展示接入示例；后续产品页面由 yyyrrrxxx 接手，负责人先补首版基线 |

借用帧的回调默认在主线程执行。同步绘制可在回调期间读取；保存或异步使用须 retain，用完 release。源负责释放自己的引用，不要在回调里释放未 retain 的引用。BitmapPixels.bitmap 只在持有引用时有效，不可修改或自行 recycle。

`CameraRgbSource` 的 start/stop/close/captureOriginal 在主线程调用；初始化需要 Context 和 LifecycleOwner。调用 stop 可重复，close 后不可重新 start。页面退到后台应 stop，销毁时 close。start 已包含旧会话清理。

`SyntheticVisionSource` 同样在主线程操作；额外提供 configure(SyntheticSettings)、setPlaying(Boolean)、step()、seekTo(Long)。step 需要先暂停。start 从样本 0 开始，即使暂停也发送首帧；seekTo 不重置 frame_id。configure 在活动会话内从样本 0 重放，在未启动时只保存配置。UI 的场景/画幅切换则主动开启新会话，防止旧截图影响新场景。播放选择与配置在 stop 后保留，close 后不可再控制。

摄像头每次只处理一帧，推理完成才关闭 ImageProxy，CameraX 使用 KEEP_ONLY_LATEST 丢弃积压帧。图像旋转一次，ML Kit 接收旋转后的 Bitmap（rotation=0），关键点、背景和原图绑定同一帧。现阶段优先正确性，Bitmap 复制与 CPU 绘制尚未做 GPU 优化。

原始拍照接口使用回调，避免在主线程压缩 RGB 图片；这是此前逻辑签名的异步实现。CapturedPhoto.bytes 由调用者拥有，上传/放弃后 close 清零。不要在上传完成前 close，也不要长期保存其数组引用。

## 验证

主机测试覆盖 13 份 JSON 双向映射、无效坐标/深度/任务状态、帧引用计数、跨线程释放、最新帧替换、旧会话拒绝及姿态归一化。

连接设备后：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :core-vision:connectedDebugAndroidTest
```

App 测试会授予本应用摄像头权限；RGB 测试需要配置摄像头的模拟器或真实手机。core-vision 测试验证旋转、不含叠加的原始拍照和停止清理。测试结果见各模块 build/reports；不要把模拟器的场景当成真实人体精度测试。

真实手机还需验证：正面全身、不同身高、抬手和转身、人物离开、横竖旋转、反复授权与前后台切换、实测帧率和发热。企业 SDK、RGB/Depth 对齐、遮挡、8–12 小时稳定性另行验证。
