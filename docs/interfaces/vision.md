# 端侧视觉接口 V0.1

[Schema](../../contracts/vision.schema.json) 约束元数据，[合成样例](../../contracts/examples) 用于无硬件联调。实际图像、mask 和深度通过进程内对象传递，不能逐帧进行 JSON/HTTP 传输。

## 坐标与关键点

视觉层将画面整理为竖正、未镜像的 canonical 图像，旋转已应用；竖正不等于强制竖屏比例。image.width/height 为整理后的像素尺寸。

- 原点左上，x 向右、y 向下；坐标在 [0,1]，像素映射为 x*(width-1)、y*(height-1)。
- left/right 指被拍摄者的解剖学左右，不是屏幕左右。正面未镜像图中，人物左肩通常在图像右侧。
- 视觉适配层负责模型坐标映射回 canonical 图。显示层负责一次水平镜像（x → 1-x），背景、衣服和所有视觉数据使用相同变换。
- 首版 FIT_CENTER 等比完整显示、允许留边；更换裁剪方式需同步调整全部坐标变换。
- 固定八个关键点：左右肩、肘、腕、髋。每点含 x、y、confidence，无可靠位置则为 null，不能假装为 (0,0)。

## 每帧输出

VisionFrame 包含 schema_version、session_id、frame_id、timestamp_ms、image、tracking_state、keypoints、standing_hint、distance_m、mask、depth，字段详见 Schema。

- timestamp_ms 为单调时钟的采集时刻，frame_id 在本次会话内递增。推理结果绑定原始帧，不能冒充最新画面的结果。
- tracking_state：tracking / partial / lost。双肩双髋都存在且 confidence ≥ 0.5 才标记 tracking；部分可靠信息为 partial；无可靠人体为 lost。0.5 是待实测的初值。
- standing_hint：ok / too_near / too_far / out_of_frame / unknown。没有有效深度与站位标定时不输出距离相关结论；RGB 边界判断可输出 out_of_frame。
- distance_m 为人体区域有效距离估计（米），未测得为 null。
- mask 可为空；非空为 UINT8 人体前景概率，0 背景、255 前景。允许低分辨率，但须覆盖相同全画幅，无隐藏裁剪。它不能单独区分手臂和躯干。
- depth 可为空；非空为已对齐 RGB 网格的 little-endian UINT16 毫米，0 无效。宽高与 RGB 相同，厂商其他格式由适配层转换。
- buffer_id 是本地句柄标识；样例不包含真实像素。mask/depth 的行步长以字节记录。

VisionCapabilities 在启动时提供 source（mock / rgb_camera / depth_camera）、rgb、pose、person_mask、aligned_depth。能力描述当前适配器支持范围；单帧可选数据仍可缺失。具体像素/纹理对象类型由 Android 骨架任务显式定义，不凭句柄猜测。

## 生命周期与模块调用

以下为逻辑签名，骨架任务映射为 Kotlin 类型；不锁定摄像机或 GPU 框架：

```text
VisionSource.start(sessionId, onFrame, onStatus)
VisionSource.stop()                         // 可重复调用
VisionSource.captureOriginal() -> CapturedPhoto
FrameLease.retain() / FrameLease.release()
TryOnRenderer.submit(frameLease, garment)
TryOnRenderer.clear()
```

回调借出帧，源在回调结束后释放其引用。异步消费者须在返回前 retain，并在显示完成、丢弃或被替换时 release；引用归零才回收。元数据不可变。渲染器最多保留最新待处理帧，不无限排队，替换旧帧必须释放。

captureOriginal 返回独立拥有、竖正、未镜像、不含衣服/UI 的 JPEG/PNG，以及 session_id、frame_id、宽高和 MIME。调用者上传或放弃后释放临时数据。对比显示时原图和结果必须用相同镜像规则。

onStatus：starting / ready / stopped / error。错误区分 PERMISSION_DENIED、CAMERA_UNAVAILABLE、MODEL_UNAVAILABLE，附可读信息，不伪装成人体跟踪失败。

## 降级与验收

四个躯干锚点满足门限时显示衣服，否则隐藏并提示调整站位；首版不沿用旧位置。无深度/mask 时允许基础叠加，在调试面板注明未启用遮挡。退出、会话变化、lost 时清理旧状态。

联调验证：无深度样例可运行；镜像后三层一致且左右名称不变；横竖画幅不产生单层裁剪；tracking→partial/lost 隐藏衣服；旧会话帧被忽略；帧替换和退出无重复释放或泄漏。

## Android 实现映射

core-contracts 已提供对应 Kotlin 类型；像素载体是 core-vision 的 BitmapPixels（竖正、未镜像 Bitmap）。VisionSource.captureOriginal 使用回调返回 Result<CapturedPhoto>，压缩不阻塞主线程。JSON 合同不变。

Canvas 绘制使用像素中心：归一化点映射为 0.5 + x*(width-1)，随后与 Bitmap 共用变换；因此镜像时仍符合 x→1-x，不产生一像素偏移。FitCenter 与诊断视图使用同一约定。

当前实现只提供 RGB 和合成源，无深度或 mask。返回 partial 的原因可能是低置信度、遮挡或身体出框；站位提示应作为引导，不表示已经进行深度测距。具体构建和所有权示例见 [Android 说明](../../android/README.md)。

RGB 输出在当前帧模型点通过有效性检查后，使用 `PoseStabilizer` 做相邻帧坐标平滑（当前测量权重 0.6）；confidence、点是否存在和 tracking_state 仍依据当前测量。缺点立即 null，停止/会话切换、画幅变化或长间隔不混合旧历史；合成点不经此滤波。它不补造丢失点或距离，也不将其他帧的异步推理当作当前结果。来源及适配范围见 [参考迁移记录](../reference-reuse.md)。

本地 BitmapPixels 可附 AlignedDepthPixels，metadata 必须与同一个 VisionFrame.depth 完全一致，行跨度/尺寸按既有 uint16 小端毫米合同校验；提供者负责真实配准与同帧。照片渲染可在真实 distance_m 可用时以 `distance_m*1000 - 20` 毫米作近似衣服平面，比较场景深度并使用 20 毫米容差；0 深度是未知。缺数据/距离时不启用，不推算假深度。该近似及软件绘制测试不证明真实手臂遮挡或设备管线已校准。当前 CameraX 源仍不提供深度。OBJ 模式只是二维骨骼驱动的三维网格投影，不使用这套近似深度作为人体三维坐标。
