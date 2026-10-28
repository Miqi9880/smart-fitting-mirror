# 四人当前任务与企业需求对应

更新：2026-10-28。已重新读取企业V1.0，原编号、优先级和实际缺口见[需求对应表](requirements-traceability.md)；逐人步骤、环境和验收见[滚动计划](weekly-2026-10-24.md)。PR #38／#39由负责人委托Codex接手修正，见[交接与验证](reports/static-cg-input-prep.md)，yyyrrrxxx不再重复修改。本页替代旧基线，不重复安排首次真实闭环、保存、全屏、48件接入或连帽款任务。

共同目标是服饰P0和共用会话／视频流程。FR-MK由面部组负责；专业量体、物理布料、会员／支付、复杂推荐训练不加入当前必做。第18周约束继续取消，完成可审阅成果即接下一项。

COS因费用已禁用，Aidge/COS链路暂停，见[状态与边界](reports/cloud-pause-2026-10-24.md)。不再上传／真实调用，保留历史成功证据；端侧预览和标明MOCK的软件开发继续，本地模型尚未部署。

| 人员／长期Issue | 当前一个主要任务 | 当前验收后继续 | 原文依据 |
| --- | --- | --- | --- |
| haiyangniu191-design／[#5](https://github.com/Miqi9880/smart-fitting-mirror/issues/5) | 真实调用禁用开关、离线失败恢复和匿名调用／费用记录，拆小PR，无付费测试 | 按已统一的[视频0.4.0合同](interfaces/video-recommendation.md)和Python DTO接内容库／规则／HTTP路由，不重复设计字段或凑5条候选 | 7.3、8.1、9／12.1；FR-VID-01／02 |
| lyf-bee／[#6](https://github.com/Miqi9880/smart-fitting-mirror/issues/6) | PR #42头部保护修正已合并，接渲染耗时／资源诊断及实际新缺陷，不重复修同一领口问题 | 可靠Depth／mask接入按负责人输入继续 | FR-VTO-04、8.2、11 |
| yyyrrrxxx／[#7](https://github.com/Miqi9880/smart-fitting-mirror/issues/7) | PR #45～#50已提交的视频组件待整体审查／验证，不重复布置首版FakeVideoRepository；不重做照片导入或待机站位 | 按[视频0.4.0映射](interfaces/video-recommendation.md)接协程HTTP／共享会话，待后端路由就绪联调；补视频异常／释放和新界面可访问性 | 8.2、10；FR-VID-01／02／05 |
| Miqi9880／[#3](https://github.com/Miqi9880/smart-fitting-mirror/issues/3)、[#26](https://github.com/Miqi9880/smart-fitting-mirror/issues/26) | [结果切换](reports/result-comparison-2026-10-28.md)、[待机／站位](reports/standby-standing-2026-10-28.md)与[视频合同／双端类型](reports/video-contract-2026-10-28.md)已补齐；继续授权视频与素材交接、审查组员播放器、真实Depth／对齐／mask条件 | 本人镜子／真人／实际站位、遮挡和性能长稳；模型资源独立探索 | FR-001～004、FR-VTO-01／02／06、8.3、11／12 |

全部现场与真人确认只归Miqi9880，其他人不用设备或云账号，交合成／模拟器／FakeProvider证据。修改影响或新缺陷才补相关回归，不重做全部历史报告。

企业重点缺口仍有Depth／对齐／mask和真实手臂遮挡、完整待机／站位、原文切换／滑杆对比、5条视频候选与播放、AI语义标签和费用治理。后台／Token统计的原文章节分期不同，已列待老师确认，没有静默删除。一次真生成成功不等于全部P0交付。

跨模块按[协作规范](collaboration.md)与CONTRIBUTING审阅。长期Assignee保持，负责人补位不算成员本人交付；待修PR不叠加另一主要功能，完成后直接取队列下一项。
