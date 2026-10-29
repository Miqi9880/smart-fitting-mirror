# 视频库与规则推荐后端增量

2026-10-29，基于已合并的 HTTP 0.4.0 合同实现后端视频推荐首轮。新增 `backend/video_library.py` 和 `POST /api/v1/video-recommendations`：只读地复用当前游客会话服装目录及主动标签，按 category／style／color／scene 的维度与值精确匹配；按匹配维度数量降序、稳定 video_id 升序排序；去重后返回实际 `available_count`，不足不补齐，空上下文不做泛推荐。

内容库 JSON 保留内部 `published`、`authorization_verified`、`playable_verified` 标志；响应只含合同字段。默认 [`backend/assets/videos/catalog.json`](../../backend/assets/videos/catalog.json) 为空。自动化测试中的 `synthetic_test` 内容只能通过显式测试注入启用，不从默认目录、会话或运行服务返回。面部妆容目录未接通，非空 `makeup_ids` 返回 `400 INVALID_REQUEST`；未知或不属于本会话的服装同样返回400。路由复用游客会话令牌，不延长会话TTL；独立每会话限流30次／分钟，错误沿用 `ErrorResponse` 和 `no-store`。

此增量没有获取、下载或播放视频，没有调用模型，没有改 Android 播放器或公共合同。项目目前没有负责人提供的5条真实授权并确认可播放视频；该项仍是数据／播放验收条件，不能把 `.invalid` 合成样例当成真实内容。

## 验证

在 `D:\MySmartMirror\smart-fitting-mirror` 的 Python 3.12 虚拟环境运行：

```powershell
.venv312\Scripts\python.exe -m pytest backend/tests/test_video_recommendation_api.py backend/tests/test_video_contracts.py -q --basetemp=.pytest-tmp-video-target
# 36 passed
.venv312\Scripts\python.exe tools/check_contracts.py
# PASS: OpenAPI 3.0.3, 2 JSON schemas, 21 fixtures, 19 rejection cases, local doc links.
.venv312\Scripts\python.exe -m pytest backend/tests -q --basetemp=.pytest-tmp-video-full
# 152 passed
git diff --check
# passed
```

测试只使用合成元数据和本地HTTP请求，不访问媒体URL或云服务；运行后未生成调用统计数据库。没有运行 Android 构建或现场播放器验证，因为本次只改后端。
