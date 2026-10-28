# 后端与 Android 网络接入

维护人：haiyangniu191-design。负责人先行补充 HttpFittingApi 与 FittingSession；无需重写客户端。协议仍为 core-contracts 的 FittingApi 与 DTO。

2026-10-28新增[视频合同0.4.0](../../docs/interfaces/video-recommendation.md)：HttpFittingApi另实现VideoApi，App通过FittingSession.recommendVideos(request)复用游客会话。后台／结束／关闭取消查询，401关闭共享会话，其余失败交视频UI显示，不修改生成状态。仅取元数据，不下载视频、不上传照片或生成；运行后端推荐路由尚待实现，旧服务404不能静默回退Fake。

HttpFittingApi 接收 origin 地址，封装会话、目录、原始上传、幂等创建/查询/取消/结束及同来源图片下载；无重定向、自动请求重放或磁盘缓存。FittingSession 输出 StateFlow，持有凭证、固定创建请求的幂等 key、控制轮询与结束清理。generate 接管 CapturedPhoto，页面不再同时释放。

具体所有权、错误和接入步骤见 [试衣联调](../../docs/tryon-integration.md)。本模块 12 项单元测试覆盖原 key 重试、取消竞争、后台暂停、清理失败、请求所有权和图片来源/大小。Android 对真实本地模拟服务的四项流程测试在 app 的 TryOnFlowTest。

本周先复现测试和服务，后续真实 AI、更多失败恢复按 [#5](https://github.com/Miqi9880/smart-fitting-mirror/issues/5) 推进。页面归 yyyrrrxxx，摄像头归 Miqi9880。
