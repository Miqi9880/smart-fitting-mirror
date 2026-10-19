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

PR #19 首轮没有复制上游模型、矢量衣服、人形、3D/SDK 二进制或整套后端。首轮迁移评估与测试范围见 [复用说明](../../docs/reference-reuse.md)；后续 OBJ 迁移如下。

## 2026-10-19 后续完整适配

负责人随后明确授权“不用很顾及分工，能迁移的全迁移”。进一步适配：

- `ui/screens/ListScreen.kt`、`DetailScreen.kt`、`AddClothesScreen.kt` 的搜索/筛选、图片卡片、商品资料、现有素材选择与编辑交互，接为本项目 Views＋现有会话/HTTP。
- `backend/.../Clothing.java`、`ClothingController`、`ClothingService`、`ClothingRepository` 的元数据与 CRUD 思路，接为 FastAPI＋SQLite 基础目录及隔离的临时游客衣柜；不复制用户 ID 作为授权依据。
- `algorithm/GarmentDriver.kt` 的 OBJ 读取、面索引和躯干/手臂区域驱动，适配为 Kotlin GarmentMesh／GarmentMeshDriver；没有默认立方体、模拟骨架回退或不安全的旧点续用。
- **实际复制** `app/src/main/assets/tshirt.obj` 至 `android/feature-tryon/src/main/assets/reference/tshirt.obj`，保留原始生成模型文件。它是简化网格，不是实拍衣服。
- `TryOnRenderer.kt`／`res/raw/garment_fs.glsl` 的图像叠加、法线明暗与深度比较思路，接为已有 Canvas 渲染与毫米单位深度遮挡；修正原 NDC/毫米混用风险，不引入假深度。
- `BootReceiver.kt` 的开机入口用途，适配为用户/管理员可选的 HOME 角色，遵循当前 Android 平台入口能力；未在本机或目标设备更改默认桌面。

固定来源提交仍为上面的 `11b667b6ec8c367395db146a7048fc047d874696`。已有 CameraX/ML Kit、网络客户端与帧所有权作为功能对应实现保留；未重复引入 MediaPipe 模型二进制、Orbbec SDK 占位、JNI 加速副本或另一套后端运行时。[完整功能覆盖及边界](../../docs/reference-full-migration.md)。
