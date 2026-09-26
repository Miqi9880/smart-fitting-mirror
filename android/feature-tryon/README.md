# 实时试衣同学接入位置

维护人：lyf-bee。当前仅是可构建模块骨架，尚无衣服素材或试衣渲染。

Miqi9880 已提供主工程、摄像头、原始拍照、权限和诊断控制，可直接复用。下一步实现真实透明衣服叠加及产品页面，详见 [T05 接手清单](../../docs/task-handoff.md)。诊断骨架线与原图弹窗不能代替试衣渲染及生成结果对比。

用 core-contracts 的 TryOnRenderer 接口消费 FrameLease<BitmapPixels> 与 Garment。背景图与关键点来自同一帧；异步持有先 retain，用完 release。参考 app 中的 VisionDebugView 获取 FIT_CENTER 和镜像示例，不另开摄像头。

诊断页用于硬件开发，后续可替换为你的试衣入口；app 入口和公共配置变更请项目负责人审阅。
