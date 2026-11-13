# 视频推荐接口 V0.4.1（推荐JSON兼容0.4.0）

2026-10-28定稿。依据企业V1.0的FR-VID-01／02／05、7.4、12.1；服饰组负责当前服装／主动标签上下文，妆容ID留作面部组协调接口，不在这里实现试妆。

**实现状态（2026-10-29视频增量）：** PR #52的只读目录／规则／鉴权路由继续使用。负责人已安装7条Pexels授权参考短片，增加公共媒体／封面Range路由，并将yyyrrrxxx组件修正后接入同一App／共享游客会话；操作和验证见[集成记录](../reports/video-playback-2026-10-29.md)。安装包0.3.8，未安装[独立媒体包](../../backend/assets/videos/README.md)时缺文件候选不返回。5条上衣参考与2条连衣裙参考已分别播放；不代表每个品类／每次请求都满5条。模型语义、镜子现场和长稳仍待验证；COS/Aidge仍暂停。

权威字段见[OpenAPI](../../contracts/openapi.json)，双端实现为`backend/video_contracts.py`和`android/core-contracts/.../VideoModels.kt`。旧[草案](video-recommendation-draft.md)转为历史依据，与本页冲突时以本页为准。

## 请求、数量与匹配

`POST /api/v1/video-recommendations` 沿用游客会话鉴权，普通请求只查询推荐。显式传入 `semantic_job_id` 时，服务可以用本机模型读取该会话已有的生成结果。客户端只传任务 ID。两种请求均沿用原会话有效期。

| 请求字段 | 约束／含义 |
| --- | --- |
| garment_ids | 默认空，至多6个不重复的当前会话服装ID；沿用衣柜ID，不能用素材标题或随手改ID |
| makeup_ids | 默认空，至多8个不重复ID；当前服饰端传空，后端未取得面部组目录时，对非空值明确报400，不静默忽略或冒充已联动 |
| user_tags | 默认空，至多20个不重复`{dimension,value}`；dimension为category／style／color／scene，value去首尾空白、非空、至多64字符 |
| semantic_job_id | 可选。仅接受当前游客会话中已成功的非 MOCK 生成任务。点击「AI 分析生成图并推荐」才发送 |
| limit | 整数1～20，默认5；不是“必须凑够”的数量 |

服务端按当前会话解析服装 ID，未知服装报 400。不可访问、未成功或 MOCK 任务报 404。空上下文返回空列表。模型语义标签由服务端生成和校验，客户端只可主动提交 `user_tags`。

首轮按**维度＋值精确匹配**，大小写敏感。品类沿用现有服饰枚举，颜色沿用目录规范值。风格、场景和颜色可由明确维护的数据、主动选择或已校验的模型输出提供。

只召回上架、来源合格且有已确认播放资料的候选。去重后至少匹配品类、风格、颜色或场景中的一个维度。先按是否命中主动偏好排序，其后依次按模型标签命中数、匹配维度数降序、`video_id` 升序排序。App 的「风格偏好」仅保存在当前游客会话内。实测覆盖与验收方法见[视频推荐验收](../reports/video-acceptance-2026-11-11.md)。

请求完整样例：[video-request.json](../../contracts/examples/video-request.json)。

## 响应与来源

衣柜原有颜色字段的展示范围大于视频标签；不能表示为VideoTag的长文本或非规范颜色被跳过，不截短或猜测为另一个颜色，其余合法品类／主动标签仍可参与召回。合法服装不会仅因此返回400；未知服装ID仍按合同拒绝。

200响应包含`items`、`requested_limit`、`available_count`：后者是**相关、可返回、去重候选截断前总数**，不是全库大小；`items.size = min(requested_limit, available_count)`，且响应的requested_limit必须等于本次请求limit。不足仍展示已有条目，不重复／补无关视频，不报成网络错误；空态为items=[]、available_count=0。

每个item包含`video`和非空`matches`：

| 对象／字段 | 固定含义 |
| --- | --- |
| video.video_id／title | 稳定ID（可含中划线）／展示标题，不按列表位置作为ID |
| duration_seconds | 正整数秒，1～86400；统一使用秒，不是毫秒 |
| orientation | landscape／portrait；可播放资源的实际方向，用于布局提示 |
| cover_url | HTTP(S)绝对地址或null；无封面给占位，不把人物生成图默认保存作封面 |
| playback_url | HTTP(S)绝对地址；不能是Windows路径、file://、content://或App专用对象 |
| tags | 非空结构化VideoTag数组；和user_tags用同一维度／值约定 |
| garment_ids／makeup_ids | 内容关联ID；可为空，不表示请求里所有服装都一定匹配 |
| source.kind | synthetic_test／licensed。测试内容必须前者；数据校验通过不等于授权已核实 |
| source.attribution／authorization | 非空来源与授权／测试说明。真实权限凭据由内容维护人核对留存，不把密钥或人像放进这里 |
| matches | 每条为dimension、value、source、related_id；维度／值必须出现在此视频tags，并由实际本次上下文支持 |

`matches.source` 为 `garment_metadata` 或 `makeup_metadata` 时，`related_id` 是本次请求对应的资源 ID。主动选择与模型匹配的 `related_id` 为 null，标签必须分别来自本次请求的 `user_tags` 和响应的 `semantic.tags`。只有显式请求语义时才返回 `semantic`，包含对应 `job_id`、`status` 和最多 4 个标签。状态为 `used` 时返回标签，为 `no_tags` 或 `unavailable` 时返回空标签和规则候选。App 显示具体来源。详见[语义标签试验](../reports/video-semantics-2026-11-11.md)。

媒体URL不带userinfo或fragment，最长2048字符。**播放器和封面加载器不把游客Authorization附加到任何响应媒体URL，也不把Token放查询串。** 首轮内容使用无需游客凭证即可访问的受控／获准资源；若将来需要受鉴权文件流，应另行约定媒体路由／请求头，不套用当前只下载PNG/JPEG的`FittingTransport.download()`。release使用HTTPS，HTTP仅限debug受控本地联调；不在日志、偏好设置或Issue保存临时签名URL。

结构样例：[有匹配](../../contracts/examples/video-matched.json)、[不足5条](../../contracts/examples/video-shortfall.json)、[空列表](../../contracts/examples/video-empty.json)。均为synthetic_test及不可访问的.invalid地址，没有实际可播放文件，不计入5条授权内容。

## 错误、取消与会话

| 状态 | 调用方行为 |
| --- | --- |
| 400 INVALID_REQUEST | 修正重复、越界或未知上下文ID，不自动重试 |
| 401 SESSION_INVALID | 沿用会话失效清理，停止播放、清列表，回待机／重连；不展示旧游客列表 |
| 429 | 保留Retry-After，提示等待；用户可明确重试，不自动重放POST |
| 503 | 展示可恢复失败与返回路径，不以Fake列表冒充服务成功 |
| 旧服务404 | 当前路由尚未接入时的兼容状态，提示服务未支持；不要重新上传／生成或静默改接口 |

错误格式沿用ErrorResponse。查询失败不破坏当前搭配、图片或贴合设置；401仍执行全会话清理。用户开始新查询／改选衣服时取消旧请求，并核对请求快照后才应用结果；退后台、返回、结束会话清除挂起加载和播放器。客户端不自动切换到Fake模式；FakeVideoRepository是明确标记的独立开发模式。

## Android接入与现有组件映射

`VideoApi.recommendVideos(token, request)`由`HttpFittingApi`实现；不向旧FittingApi增加抽象方法，旧Fake不必重写。App应通过`FittingSession.recommendVideos(request)`调用，复用内部Token，在同一ViewModel／UI调度器的协程调用，勿在主线程同步阻塞。该入口在后台／结束／关闭取消挂起查询，拒绝结束后的旧响应；401关闭共享会话，其余错误交给视频UI处理，不能改生成状态或自动重试。

已核对yyyrrrxxx的PR #46（b7bef67）的本地VideoItem／VideoQuery／VideoRepository类型，首个播放器增量可保留：

| 当前播放器类型 | HTTP适配 |
| --- | --- |
| VideoQuery.garmentIds | request.garmentIds；当前例子中的studio_black_tee等测试ID不可直接当真实目录ID，使用所选Garment.garmentId |
| VideoQuery.tags: List<String> | UI选项明确维度后转换为userTags，不从自由字符串猜维度；HTTP结果tags转换为显示文本即可 |
| VideoItem.videoId／title／durationSeconds／orientation | 映射item.video同名字段；方向枚举做显式映射，单位保持秒 |
| VideoOrigin.SYNTHETIC_TEST／LICENSED | 映射video.source.kind，同时展示来源说明；不按标题是否含“测试”猜来源 |
| GeneratedClips本地资源 | 仅本地测试模式使用；HTTP模式用playbackUrl，不静默换成生成样片 |
| VideoResult.Items／Empty／Failure | 部分候选仍为Items并显示数量；仅0条为Empty；网络／合同异常为Failure，取消不显示成失败 |
| 同步VideoRepository | 保留本地Fake；HTTP加载增加协程适配，勿在同步recommended里阻塞网络 |

本轮由负责人按已发布接手范围修正并整合VideoPanel；产品通过VideoExperience嵌入TryOnActivity、启用home.video。VideoActivity保持内部合成测试宿主，不导出、不用于产品入口。查询被后台取消后显示显式重试，不自动重放POST；播放在旋转／后台后恢复为暂停。

## 分工与验收

- Miqi9880／受托Codex本轮完成公共字段／版本、双端DTO、共享样例、客户端与会话门面、验证说明。
- haiyangniu191-design在现有调用治理增量后，基于`backend.video_contracts`交付的后端库／路由见PR #52；负责人复核及两项补修见[复核记录](../reports/pr52-review-2026-10-29.md)。验收覆盖未知服装／未接妆容400、去重、相关性、不足与空态、会话隔离／过期／撤销。不得重复定义字段，也不需恢复COS／收费AI。
- yyyrrrxxx的PR #46～#50原始提交保留作者，源码移到正确包目录，负责人完成修正／HTTP／主页／生命周期集成；#45占位文件不导入。组件与集成不再重复布置；下一项仅视频页真实TalkBack朗读／焦点查漏，见[当前任务](../tasks.md)。
- 负责人本轮取得7条授权参考片并核验实际播放，上衣上下文至少5条的样例通过；相关数量仍据请求而定，不凑数。全部镜子／真人现场只归负责人，无需其他成员准备手机或接相机。

合同检查`python tools/check_contracts.py`，Python DTO检查`python -m pytest backend/tests/test_video_contracts.py -q`；Android `:core-contracts:test :core-network:testDebugUnitTest :core-network:lintDebug :app:assembleDebug`。这些验证不代表HTTP服务／视频播放已联通，实际命令和结果见[本轮记录](../reports/video-contract-2026-10-28.md)。

## 0.4.1公共媒体

`GET /api/v1/video-media/{video_id}.mp4`、`GET /api/v1/video-covers/{video_id}.jpg`均支持HEAD，200／206提供公共内容，支持Range、Accept-Ranges及Content-Range；越界Range为416，未知／未上架／未安装为404。只从已核验目录解析固定文件，不能指定路径。MP4为H.264/yuv420p、24fps、最长12秒、已移除音轨；封面JPEG。元数据URL由当前请求服务地址生成，局域网／模拟器可分别使用；反向代理必须正确保留已批准的外部主机及协议，生产使用HTTPS。

这些公共穿搭参考内容无游客数据，媒体请求不带Authorization，Cache-Control为public,max-age=3600。推荐元数据仍需共享Token且no-store；游客照片／生成结果仍受鉴权隔离。结束会话停止播放器和列表，但不撤销本来就公开的图库文件。没有装媒体包时返回空态，不回退到合成片；详见[内容安装与许可](../../backend/assets/videos/README.md)。
