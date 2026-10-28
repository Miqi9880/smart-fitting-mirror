# 视频推荐接口 V0.4.0

2026-10-28定稿。依据企业V1.0的FR-VID-01／02／05、7.4、12.1；服饰组负责当前服装／主动标签上下文，妆容ID留作面部组协调接口，不在这里实现试妆。

**交付状态：本轮交付合同、共享JSON样例、Python／Kotlin数据类型、Android HTTP方法及游客会话调用入口。当前backend/service.py还没有此路由，视频库、规则召回和HTTP路由仍由haiyangniu191-design接入；播放器仍由yyyrrrxxx接入。** 不能把合同版本当作运行服务已支持、已取得5条可播放视频或完成企业推荐闭环。旧0.3.0试衣路径保持；视觉仍0.2.0，APK仍0.3.7，本轮不要求重装镜子。

权威字段见[OpenAPI](../../contracts/openapi.json)，双端实现为`backend/video_contracts.py`和`android/core-contracts/.../VideoModels.kt`。旧[草案](video-recommendation-draft.md)转为历史依据，与本页冲突时以本页为准。

## 请求、数量与匹配

`POST /api/v1/video-recommendations`，`Content-Type: application/json`，沿用当前`Authorization: Bearer <session_token>`。不创建另一个游客会话，不传人物图、生成图、身份或Token字段；Token只在鉴权头。POST用于有界结构化查询，**只读**，不上传、不生成、不调用收费VLM／LLM、不延长会话TTL，也不需要生成任务的Idempotency-Key。

| 请求字段 | 约束／含义 |
| --- | --- |
| garment_ids | 默认空，至多6个不重复的当前会话服装ID；沿用衣柜ID，不能用素材标题或随手改ID |
| makeup_ids | 默认空，至多8个不重复ID；当前服饰端传空，后端未取得面部组目录时，对非空值明确报400，不静默忽略或冒充已联动 |
| user_tags | 默认空，至多20个不重复`{dimension,value}`；dimension为category／style／color／scene，value去首尾空白、非空、至多64字符 |
| limit | 整数1～20，默认5；不是“必须凑够”的数量 |

服务端按当前会话目录解析ID，不认识／不属于该会话的ID报400 `INVALID_REQUEST`；不能把坏ID当空上下文。空上下文合法，返回空列表，不泛推无关内容。不接受客户端任意指定标签来源或模型语义；模型标签来源只预留枚举，当前没有实现FR-VID-03。

首轮按**维度＋值精确匹配**，大小写敏感。category使用现有服饰枚举，如upper_body、lower_body、dress；颜色沿用目录`details.colors`的规范值。style／scene只取明确维护的元数据或用户选择，不从photo、official-studio等素材标记猜风格。不做隐式中英文转换；确需别名表时由后端维护并给双方相同选项。

只召回上架、来源合格且有已确认播放资料的候选；去重后，至少category／style／color／scene中的一个维度相关。关联服装ID是辅助线索，不能代替四个维度的可解释相关性。首轮排序规则由后端确定并保持稳定，建议匹配维度数降序、video_id升序作为同分顺序，不在这个接口引入训练推荐模型或收费重排。

请求完整样例：[video-request.json](../../contracts/examples/video-request.json)。

## 响应与来源

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

matches.source为garment_metadata／makeup_metadata时，related_id必须是本次对应上下文资源ID；user_selection时为null且必须来自本次user_tags。model_semantics仅预留，当前不得伪造；本轮客户端和Python的validate_context拒绝此来源，后续接入需明确触发、来源和费用条件并同步升级调用方。匹配证据可以产生简单说明，但不表示P1的自然语言推荐理由或AI语义已交付。

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

本轮不覆盖她的VideoPanel／VideoActivity文件、不擅自打开home.video；等待后端路由与播放器接入分别验收后再启用同一App入口。

## 分工与验收

- Miqi9880／受托Codex本轮完成公共字段／版本、双端DTO、共享样例、客户端与会话门面、验证说明。
- haiyangniu191-design在现有调用治理增量后，基于`backend.video_contracts`实现内容库、上架／授权／可播过滤、稳定规则、上面的路由与鉴权／限流／ErrorResponse。400必须覆盖未知服装／未接妆容；测试去重、相关性、不足与空态、会话隔离／过期／撤销。不得重复定义字段，也不需恢复COS／收费AI。
- yyyrrrxxx先处理已提交PR #45～#50的视频组件交付和构建／交互证据，随后按此映射接HTTP与会话入口；补取消、过期、空态／不足／失败和播放器释放。不要重复待机、站位、时钟或原图切换。
- 至少5条确实相关且可播放的自有／授权内容仍由负责人协调，缺失继续列阻塞；全部镜子／真人现场只归负责人，无需其他成员准备手机或接相机。

合同检查`python tools/check_contracts.py`，Python DTO检查`python -m pytest backend/tests/test_video_contracts.py -q`；Android `:core-contracts:test :core-network:testDebugUnitTest :core-network:lintDebug :app:assembleDebug`。这些验证不代表HTTP服务／视频播放已联通，实际命令和结果见[本轮记录](../reports/video-contract-2026-10-28.md)。
