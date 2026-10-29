# 后端与服装接口

2026-10-29：HTTP合同[视频增量V0.4.1](video-recommendation.md)保留0.4.0推荐JSON，增加公共授权媒体／封面GET、HEAD与Range。PR #52的目录／规则／路由现接7条授权参考短片和同一App播放器，见[记录](../reports/video-playback-2026-10-29.md)；媒体包需另装，缺文件返回空库。

[OpenAPI 合同](../../contracts/openapi.json) 定义字段和响应，本文定义行为。采用 [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3)，合同不表示已有可运行服务。

## 通用规则

API 前缀 /api/v1，JSON 使用 snake_case，时间为 UTC RFC3339。Base URL 可配置。

POST /sessions 创建游客会话，返回本项目的 session_token；其余会话业务请求使用 Authorization: Bearer，不使用供应商密钥。公共授权视频／封面不携带游客凭证。服务端校验上传、任务归属，不能仅靠客户端传来的 session_id 授权。

模拟服务在本机或受控网络运行，跨网络真实传图使用 HTTPS。服务端对会话创建、上传和生成限流。错误格式统一为 error:{code,message,retryable,request_id}，不返回堆栈或密钥。

## 首轮接口

| 方法与路径（省略前缀） | 用途 | 成功 |
| --- | --- | --- |
| POST /sessions | 创建游客会话 | 201 Session |
| GET /garments | 上衣、裤子及帽子目录，不分页 | 200 GarmentList |
| GET /garments?q=...&tag=... | 名称/品牌/材质/标签搜索及标签筛选 | 200 GarmentList |
| GET /garments/{garment_id} | 当前会话服装详情 | 200 Garment |
| POST /garments | 在当前临时衣柜新增，使用 GarmentWrite | 201 Garment |
| PUT /garments/{garment_id} | 编辑当前临时衣柜条目，路径/正文 ID 一致 | 200 Garment |
| DELETE /garments/{garment_id} | 从当前临时衣柜移除 | 204，无正文 |
| POST /uploads | multipart 上传原始照片，字段 file | 201 Upload |
| POST /tryon-jobs | upload_id、garment_id 创建任务 | 202 Job；幂等重放 200 Job |
| GET /tryon-jobs/{job_id} | 轮询状态 | 200 Job |
| POST /tryon-jobs/{job_id}/cancel | 请求取消 | 200 当前 Job |
| DELETE /sessions/current | 撤销与清理 | 204，无正文 |

视频推荐与公共媒体流已接入，字段和路由见[视频接口](video-recommendation.md)；后台管理完整页面和统计扩展另行推进。

## 素材与上传

Garment 包含编号、名称、category、标签、缩略图、preview_image 和 generation_reference_url。HTTP 0.3.0 新增 `dress`、`skirt`、`shorts` 和裙摆长度字段，两端需一起更新；旧客户端不能保证识别新增枚举。视觉帧仍为 0.2.0 的同帧十四点，能力 Schema 仍为 0.1.0。

| category | preview_image.anchors | preview_image.attachment_anchors |
| --- | --- | --- |
| upper_body | 原双肩双髋四点 | 可省略 |
| lower_body | null | left/right_hip、left/right_knee、left/right_ankle 六个源素材点 |
| headwear | null | left_ear、right_ear 两个源素材点 |
| dress | 双肩双髋四点 | left/right_hip、left/right_hem；髋点与 anchors 一致 |
| skirt / shorts | null | left/right_hip、left/right_hem 四个源素材控制点 |

`dress`、`skirt`、`shorts` 另外要求 `preview_image.hem_ratio`：0.1–1 在髋→膝之间插值，1–2 在膝→踝之间插值；短裤只能 ≤1。它是单件素材的初始长度标定，不是人体测量。hem 是服装裙摆／裤脚控制点，不伪装成真实膝踝。短裙／短裤不依赖踝点，长裙／连衣裙需要完整可靠腿部；连衣裙还需双肩。裙摆连续绘制，短裤按两条大腿绘制，不能互套长裤几何。

搭配中长裤／半裙／短裤互斥；连衣裙与上衣及所有下装互斥，帽子保留。上衣仍每次一件，尚无多上衣叠穿或整套生成。

所有点均为素材图片中的归一化坐标，解剖学左右不变。不把帽子或裤子的点塞进肩点字段。写入品类必须与 asset_id 对应的素材定位方式一致，否则 422 INVALID_ASSET。示例见 [组合目录](../../contracts/examples/outfit-garments.json)。运行中的默认目录与本地素材包见 [复现说明](../studio-outfits.md)；文件未安装时素材接口返回 503 ASSET_NOT_INSTALLED，提示安装命令，不回退到假衣服。

0.1.1 新增可选 `details`：brand、material、description、sizes、colors、season、care_instructions、price_cents（整数分或 null）。旧目录不带此字段仍可读取；空默认详情不改变旧 JSON 的双向映射。`GarmentWrite` 指定 garment_id、name、现有素材库 asset_id，可附 category/tags/details；不接受任意远程图片 URL。重复 ID 返回 409 GARMENT_EXISTS，不存在返回 404，非法素材返回 422 INVALID_ASSET；0.3.0 每会话最多 100 件，满额可更新／删除，新增返回 429 CATALOG_CAPACITY。

搜索以空格分词并要求各词匹配，忽略大小写；中文多字词允许字符宽松匹配。tag 是精确标签。空查询返回全部。目录更改按会话隔离，结束/过期时清理；已创建任务固定其素材快照，后续编辑/删除目录不改变在途任务。

CLI 运行后端默认使用 SQLite 保存内置基础目录，管理方式见 [完整迁移说明](../reference-full-migration.md)。本机持久化基础目录与游客 API 临时编辑分开；新会话读取基础目录快照。它不保存人像/令牌，也不新增公开的无授权管理接口。

预览素材为正面、未镜像、透明背景 PNG。锚点左右指假设穿着者的解剖学左右，髋锚点是身体对齐位置，不强制位于下摆。尺寸和锚点必须对照真实素材校验。云端参考图可与预览素材不同。

generation_reference_url 为空时，该服装创建生成任务返回 422 GARMENT_NOT_GENERATABLE。服务端按 garment_id 查素材，不接受客户端任意远程 URL。样例 .invalid URL 不可下载；模拟服务任务需准备原创或授权可用素材。

上传 JPEG/PNG，初始限制 10 MiB、1600 万像素，服务端检查实际类型和解码尺寸。原图不含衣服/UI，不保留不必要 EXIF。限制是开发初值，选定供应商后复核。

上传返回 upload_id、宽高和 expires_at。输入最多保存 30 分钟且不超过会话有效期，可在用完后提前清理；不跨会话复用。

## 幂等与任务状态

POST /tryon-jobs 必须带 Idempotency-Key（随机 UUID）。服务端按会话和 key 保存输入指纹及任务：同 key 同输入返回同一任务，不同输入返回 409 IDEMPOTENCY_CONFLICT。映射保留到会话结束。创建请求超时后重试使用原 key，不新建计费任务；新任务用新 key。重新上传产生新 upload_id 时不能沿用已绑定旧输入的 key。

queued → running → succeeded / failed / cancelled；queued 也可直接 failed/cancelled。终态不可回退。

- succeeded：result 非空、error 为空；failed：error 非空、result 为空；其余状态两者均为空。
- is_mock=true 必须显示“模拟结果”，不能当成真实换装。
- 结果含 image_url、宽高和 expires_at。资源访问须受会话授权或可撤销短期机制控制，不公开人像；地址随会话失效。
- 客户端每 2 秒轮询；网络超时不等于任务失败，不自动新建任务。离开页面暂停，回来查询原 job_id。
- 开发初值：服务端任务总时限 120 秒，超时 failed / GENERATION_TIMEOUT；客户端等待较久可继续查询或取消，不伪造终态。
- 429 读取 Retry-After 秒数；网络错误和 5xx 有上限退避。结束会话或取消后停止轮询。
- 取消和成功以服务端先提交的终态为准。已成功/失败时取消返回原终态；先取消则迟到结果丢弃。不能保证供应商取消已发生的费用。

## 会话结束

V0.1 会话固定 30 分钟，无续期。客户端提前提示，服务端到期自动清理。DELETE 返回前撤销令牌和资源访问、取消未完成任务；本项目临时文件在 60 秒内删除。第三方留存需在接真实服务前确认。

同一已撤销令牌重复 DELETE 仍为 204，其他请求为 401。其他会话的资源访问返回 404。客户端过滤旧会话回调。当前接口不提供永久保存或跨会话找回结果。
