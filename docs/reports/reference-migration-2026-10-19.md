# SmartMirror 平滑模块迁移验证

日期：2026-10-19（Asia/Shanghai）。由 Miqi9880 委托 Codex 实施与验证；本次不是团队共同验收。对应 [迁移清单](../reference-reuse.md) 和 [来源/课程复用记录](../../third_party/SmartMirror/NOTICE.md)。

## 本次交付

从 `J0KER-N/SmartMirror@11b667b6ec8c367395db146a7048fc047d874696` 的 `MediaPipeBodyTracker` 适配逐关节指数平滑，当前测量权重为 0.6。实现位于 `core-vision/PoseStabilizer`，接在现有 CameraX＋ML Kit 的当前帧推理之后；不改公开字段、依赖、帧所有权或其他成员模块。

缺失/低置信度点立即置空，lost 没有旧点；恢复、停止/新会话、画幅变化、超过 250 ms 断帧或不递增时间戳时不复用相应历史。confidence 保留当前值，没有导入上游固定深度、模拟人体回退或异步旧姿态配新图像。

## 环境与实际检查

基线为 main `8ef81d8d08d5ccf6adaf89efc516009d2cdc37f2`，实现分支 `feat/reference-pose-stability`。Windows、JDK 21.0.10、项目 Gradle Wrapper、SDK/API 35；模拟器为 Android 15 / x86_64 / 1080×2400。没有连接目标镜子或外接实物摄像头，也未启动生成后端。

在 `android` 目录执行：

```powershell
.\gradlew.bat --no-daemon :core-vision:testDebugUnitTest :core-vision:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat --no-daemon '-Pandroid.testInstrumentationRunnerArguments.class=com.smartfitting.mirror.LaunchTest' :app:connectedDebugAndroidTest --console=plain
```

| 检查 | 实际结果 | 说明 |
| --- | --- | --- |
| core-vision JVM | 17 项通过，0 失败/跳过 | 新增 PoseStabilizerTest 8 项；原 PoseMapperTest 4 项、SyntheticSceneTest 5 项 |
| 新平滑用例 | 通过 | 数值/当前置信度、交替噪声、缺点恢复、lost 恢复、低置信度、断帧/时间回退、尺寸变化、重置/独立实例 |
| core-vision lint | 0 错误、7 条原有警告 | 6 条依赖版本提示、1 条既有 SyntheticVisionSource 的 KTX 建议；未为此变更依赖 |
| App debug 构建 | 通过 | 当前 APK 编译成功 |
| LaunchTest，API 35 | 2 项通过，0 失败/跳过 | 启停/镜像/重建状态，以及真实相机模型回调与后台恢复 |
| 手动 RGB 页面 | 通过本次链路检查 | 安装当前 APK，从公开试衣入口切到 RGB，显示人物图像及 `RGB 摄像头 · TRACKING · 无深度/遮挡` |

手动检查将获许可的 [Pexels 人物照片 6311639](https://www.pexels.com/photo/black-man-holding-t-shirt-standing-against-white-background-6311639/) 注入模拟器前置相机，经实际 CameraX 图像和 ML Kit 模型处理。原始照片 SHA-256 为 `3b98aed2f9237d2b26d32c3b48fca619ced2cfcc0a366f9bc220df6bd15f8d69`。截图及完整日志保留本机工作目录，公开报告不提交人物图像。

初次测试编译因跨模块属性不能 smart cast 失败，改为本地变量后重跑通过；一次仪器命令因 PowerShell 未引用 `-P` 参数而未执行测试，引用参数后重跑通过。以上表格记录修正后的最终结果。

静态合同/文档链接检查用仓库根目录的 `python tools/check_contracts.py`。机器可读用例摘要见 [证据](evidence/reference-migration-2026-10-19.json)。

## 没有由本次证明的事项

静态照片输入不是现场真人运动。本次没有测出真人实时抖动改善幅度、跟随延迟或帧率，也没有验证目标 ZC-3588A 的安装、相机协议、性能、深度/遮挡与长时间稳定性。0.6 平滑会增加一定滞后，需在真实运动中继续调校。

本次未接入实拍服装、3D 网格或上游 Compose 页面；没有运行完整后端/App HTTP 端到端测试。V07 等比例合成输入仍属负责人后续任务，lyf-bee 的实拍素材/调试点、haiyangniu191-design 的目录/服务、yyyrrrxxx 的入口/开关仍按 [周计划](../weekly-2026-10-19.md) 执行。#3/#6/#7/#15 不因本次迁移关闭。
