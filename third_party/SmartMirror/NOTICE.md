# SmartMirror 课程复用记录

- 上游项目：[J0KER-N/SmartMirror](https://github.com/J0KER-N/SmartMirror)。
- 固定来源提交：`11b667b6ec8c367395db146a7048fc047d874696`（2026-10-19 核对）。
- 来源文件：[MediaPipeBodyTracker.kt](https://github.com/J0KER-N/SmartMirror/blob/11b667b6ec8c367395db146a7048fc047d874696/app/src/main/java/com/smartmirror/app/algorithm/MediaPipeBodyTracker.kt)，`processMediaPipe` 中的指数平滑块及 `SMOOTHING_FACTOR=0.6`。
- 原实现归上游作者所有；本项目保留来源，不将迁移部分描述为独立原创。

## 使用依据

2026-10-19，项目负责人 Miqi9880 在本项目会话中明确确认“老师明确允许课程项目复用”，并授权迁移可用部分。本次据此进行课程项目的适配复用。

核对时上游没有 LICENSE 文件，GitHub 许可字段为空。本记录是负责人提供的课程复用许可范围和来源说明，不替上游发布 MIT/Apache 等通用许可，也不宣称商业用途或任意第三方再许可已获授权。后续若取得作者单独许可文本，可补充原文及适用范围。

## 已适配的部分

`android/core-vision/.../PoseStabilizer.kt` 与 `CameraRgbSource` 的调用：

1. 将 3D Joint3D 的 21 关节平滑块适配为项目现有 8 个归一化 2D 关键点，继续使用 0.6 当前测量权重。
2. 只平滑当前实际检出且置信度合格的点；confidence 使用当前值，缺点立即 null，lost 不保留旧点。
3. 每次相机 Run 独立持有状态；停止、尺寸变化、超过 250 ms 的间隔或不递增的时间戳使历史失效。
4. 保留当前 CameraX＋ML Kit、帧所有权与 HTTP 合同，没有引入 MediaPipe/NDK、假深度、模拟人体回退或异步旧结果配新图像。

没有复制上游模型、矢量衣服、人形、3D/SDK 二进制或整套后端。迁移评估与测试范围见 [复用说明](../../docs/reference-reuse.md)。
