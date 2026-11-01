# 本地试衣模型内部协议草案V0.1

2026-11-01为[11/2计划](../weekly-2026-11-02.md)协调负责人模型服务与后端适配组件。**这是未上线的内部草案**，不是已实现路由、不是Android公共合同，也不表示权重已部署。公共HTTP仍0.4.1，`TryOnProvider.generate(model_image, garment_image, cloth_type, budget)`及`ProviderResult(data,width,height)`不变。首期只做上衣`tops`。

## 连接边界

模型服务由负责人建立独立环境，首期只绑定服务器loopback。后端通过显式配置的loopback地址或负责人SSH转发访问；配置示例`http://127.0.0.1:8787`只是计划端口，本轮没有启动。适配组件默认未配置、不自动注册到CLI／公共服务，实际接入另开PR。

不给前端任意指定内部地址，不发送游客Token、session_id、公开临时URL或文件系统路径。不为这条本地链路使用COS／Aidge，也不把远端服务暴露为无鉴权公网接口。只有负责人处理服务器凭证和真实数据授权；成员用本地stub即可。

## 计划的请求与响应

| 方法与路径 | 内容 |
| --- | --- |
| GET /internal/health | JSON：`ready`布尔、`is_mock`布尔、`model_id`字符串或null；均为服务运行事实，model_id用非敏感版本名 |
| POST /internal/tryon | multipart：`person_image`、`garment_image`为实际PNG／JPEG字节；`cloth_type=tops`；`budget_ms`为本次剩余预算毫秒，整数1—120000 |
| POST成功 | 200，`Content-Type: image/png`，正文为实际生成PNG，不是下载URL；负责人在部署记录保存模型与预处理版本 |
| POST错误 | JSON `error:{code,message,retryable}`，安全短消息，不返回堆栈、私有路径、原始供应商响应或凭证 |

适配组件只允许`ready=true`且`is_mock=false`的真实服务；fake测试必须由构造参数显式允许`is_mock=true`，报告仍标测试，不能生产默认放行。服务未就绪不回退假图成功。健康与任务使用同一个显式端点，禁止跳转后转发私有图片。

输入验证复用`provider_images.MAX_SOURCE_BYTES`／`MAX_SOURCE_PIXELS`所代表的上限（现为每张64MiB／1600万像素），不新增比公共原图标准化结果更小的隐含限制；实际模型预处理／分辨率由负责人模型服务负责。模型服务同样验证尺寸、类型和解码，不接收客户端指定的任意文件路径。结果复用`tryon_provider.MAX_RESULT_BYTES`／`MAX_RESULT_PIXELS`（现为16MiB／1600万像素），有界读取后解码，校验PNG及真实宽高，返回`ProviderResult`。

## 失败、预算与测试

| HTTP／内部码 | 适配行为 |
| --- | --- |
| 400 INVALID_INPUT | 本次输入无效，映射为安全的ProviderFailure，不自动重放 |
| 422 UNSUPPORTED_CLOTH_TYPE | 当前仅tops；不宣称裤子／裙装／帽子已支持 |
| 503 MODEL_NOT_READY／MODEL_BUSY | 明确暂不可用／忙碌，不隐形排队、不改走MOCK；保留retryable含义 |
| 504 INFERENCE_TIMEOUT | 提示超时，旧任务迟到不能覆盖公共终态 |
| 连接异常／非200／坏图片 | 明确提供方失败，释放读取缓冲及临时资源，不打印整段响应 |

适配组件复用传入`GenerationBudget`，健康检查、连接／读取、结果解码均消耗同一剩余预算，返回前再次检查；不再给每一步一份完整120秒。HTTP POST不自动重试，幂等及取消仍由公共服务负责。先完成独立组件／stub测试，真实接入时再补调用统计、单任务互斥、结束／取消交接，不重复搭一套游客会话。

网络断开或公共取消不代表CUDA算子已经中止。模型服务的GPU占用由负责人在实际部署后验证，不能因客户端断开就宣称上游停止／释放，或立即再启动重叠任务。本周不要求后端同学实现GPU队列或训练器。

fake测试包括健康未就绪／测试标志、正常程序图PNG、品类错误、过大／非图片／坏尺寸、忙碌、慢响应／剩余预算耗尽及迟到结果；不依赖真实模型、真实人像、网络模型仓库或GPU。若库选型、状态码或字段需要变化，双方先在#3／#5更新此草案，再实现，勿各定义一套。
