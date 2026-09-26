# 合成联调样例

这些 JSON 不包含人像、像素或可用凭证。固定日期仅为格式示例；运行模拟服务时应动态生成有效期。所有 .invalid URL 都是占位值，不能直接显示图片。

视觉：capabilities-rgb.json 描述无深度适配器；vision-tracking.json 是正面未镜像的八点样例；vision-lost.json 用于验证隐藏与清理。buffer_id 只占位，实际内存或纹理由实现提供。

网络：session → garments → upload → create-job → job-queued → job-running → job-succeeded。job-failed、job-cancelled 和 error 分别验证失败、取消与请求错误。所有任务 is_mock=true。

T03 负责人实现运行中的模拟服务，并提供可访问的演示图片；本目录只给出结构和状态。T05 可先用这些 JSON 和原创/授权素材进行本地 UI 联调。

校验方式见 [协作规则](../../CONTRIBUTING.md)。
