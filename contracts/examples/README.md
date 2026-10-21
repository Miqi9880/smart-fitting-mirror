# 合成联调样例

这些 JSON 不包含人像、像素或可用凭证。固定日期仅为格式示例；运行模拟服务时应动态生成有效期。所有 .invalid URL 都是占位值，不能直接显示图片。

视觉：capabilities-rgb.json 描述无深度适配器；vision-tracking.json 为视觉 0.2.0 的十四点结构（新增腿部、双耳字段可为 null）；vision-lost.json 用于验证隐藏与清理。buffer_id 只占位，实际内存或纹理由实现提供。能力 Schema 仍为 0.1.0。

网络：session → garments → upload → create-job → job-queued → job-running → job-succeeded。job-failed、job-cancelled 和 error 分别验证失败、取消与请求错误。所有任务 is_mock=true。

HTTP 0.1.1 新增 garment-details.json 与 garment-write.json：包含可选详情及临时衣柜编辑请求。详情样例是实拍来源、辅助去背景服装的元数据，不含人像，图片地址仍为不可下载的 .invalid 占位。本目录现在共 15 份 JSON；原有 13 份仍保留兼容检查。

T03 负责人实现运行中的模拟服务，并提供可访问的演示图片；本目录只给出结构和状态。T05 可先用这些 JSON 和原创/授权素材进行本地 UI 联调。

HTTP 0.2.0 增加 outfit-garments.json，覆盖上衣、裤子和帽子的真实定位字段；本目录共 16 份 JSON。图片 URL 仅表示本机运行服务后的地址，不含图片内容。品牌原图和透明处理图不随 Git 分发。

校验方式见 [协作规则](../../CONTRIBUTING.md)。
