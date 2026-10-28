# 视频接口统一记录

2026-10-28，负责人委托Codex统一企业FR-VID-01／02／05的视频公共边界；正式约定见[视频合同V0.4.0](../interfaces/video-recommendation.md)，视觉帧保持0.2.0，APK仍0.3.7。

## 本轮交付

- OpenAPI新增POST /api/v1/video-recommendations、结构化上下文与来源／匹配说明，4份共享JSON。候选不足和空列表都是正常响应，禁止重复／无关凑数。
- Python video_contracts提供严格DTO与validate_context，Kotlin core-contracts提供相同数据类型和跨字段校验。未知模型语义来源、错误上下文／数量、重复ID、危险URL不会当作有效候选。
- HttpFittingApi实现独立VideoApi；不更改旧FittingApi抽象方法。FittingSession提供复用内部游客Token的推荐入口，不上传或生成，后台／结束／关闭取消查询，401沿用全会话失效清理，其余失败不破坏当前搭配／结果。不自动重放POST，也不自动切换Fake。
- 已读取yyyrrrxxx新PR #46的VideoItem／VideoQuery／VideoRepository，正式文档列出字段、秒单位、来源、协程和GeneratedClips的适配点；未覆盖其未合并播放器，也未将测试资源算作授权候选。

**边界：没有修改backend/service.py或增加实际推荐路由，没有实现视频库／召回或播放器，没有取得5条授权内容，也没有接入模型语义、收费AI、扫码／收藏。** 本轮是公共合同与消费边界交付，不能宣称企业视频闭环已运行；旧服务请求此路径仍可能404。后端与播放器按各自原职责继续接入，所有现场仍仅归Miqi9880。

## 验证

```bash
python tools/check_contracts.py
python -m pytest backend/tests -q
# 在android目录
gradle --no-daemon :core-contracts:test :core-network:testDebugUnitTest :core-network:lintDebug :app:assembleDebug
git diff --check
```

本地结果：OpenAPI及21份样例、19个拒绝用例和文档链接通过；后端全量136项通过；core-contracts21项＋core-network28项（共49项）JVM通过、0跳过，App构建与core-network lint通过。新增Python DTO用例27项，新增JVM13项；覆盖字段往返、不足／空态、错误数量／重复／来源、鉴权和限流、请求取消、当前上下文校验、共享会话失效及后台／结束取消。未新增页面，本轮没有运行原生播放器或镜子交互测试。

自审补上并发过期边界：视频查询等待时可能已开始衣柜／生成工作，查询401必须取消并使其他旧请求版本失效。新增“视频过期后迟到衣柜响应不得恢复会话”回归，最终重跑上述49项JVM、构建与lint通过；后端未受此补修影响，不重复运行已通过的全量用例。

后端全量首次因Windows旧pytest临时目录无法枚举，48项在setup失败；没有改产品代码或旧目录权限。重跑使用本任务内全新且未存在的`--basetemp`目录，136项全部通过（32.88秒），保留两次日志。测试使用仓库合成JSON、Fake及MockWebServer回环请求；没有启动或访问真实云服务，没有读取密钥、打开摄像头或连接镜子。不把MockWebServer收包校验当作backend/service.py的视频路由联通或真实媒体播放。
