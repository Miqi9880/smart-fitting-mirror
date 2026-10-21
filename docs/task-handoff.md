# 已有基础与四人接手清单

2026-10-21 增量：负责人委托补位新增 [官网商品素材与三品类组合预览](studio-outfits.md)。默认四件旧素材退出正常目录，三款平铺上衣、裤子、针织帽从本地素材包安装；HTTP/视觉合同升级到 0.2.0。lyf-bee 接手三品类真人贴合，haiyangniu191-design 接手本地包与目录迁移复现，yyyrrrxxx 接手组合/旋转/清理与大屏交互。原责任人保持，不把补位代码计作成员个人交付；旧报告作为历史证据保留。

更新：2026-10-19。长期四人分工保持，当前增量改为 [真实服装与真人预览](weekly-2026-10-19.md)，逐人见 [任务卡](tasks.md)。下表保留基础实现交接，不能把先行补位当作全部功能完成。

最新补位从 main `0db06f4` 开始，已将老师参考项目的可运行软件功能接入现有工程，见 [完整迁移与复现](reference-full-migration.md) 和 [本轮证据](reports/reference-full-migration-2026-10-19.md)。V07 输入/绘制回归、衣柜搜索/详情/编辑、SQLite 基础目录、实拍来源素材、调试点、OBJ 投影与设备信息入口都有交付；共同联调、真人运动、手工保真素材及目标设备/SDK 继续单独验证。代码属于负责人委托补位，不计作其他成员本人实现/验收。

实拍来源的白色上衣已以 [辅助去背景版本](../backend/assets/real/README.md) 接入，附初始逐件锚点。它不是纯手工保真的抠图；lyf-bee 继续比较原图、边缘和真人贴合。haiyangniu191-design 复现目录/持久化并核对真实手机断流，yyyrrrxxx 复现新页面与大屏/异常状态，Miqi9880 负责真人与实际设备。各人不重写本轮已有功能，剩余交付见 [有效周任务](weekly-2026-10-19.md)。

| 交付 | 实际状态和证据 | 下一步 / 接手人 |
| --- | --- | --- |
| Android 骨架/类型/视觉/诊断 | PR [#8](https://github.com/Miqi9880/smart-fitting-mirror/pull/8) 已合并，main 提交 52a887a；23 JVM +10 模拟器测试，见 android-validation | Miqi9880 继续真实人体、设备/SDK 核对，不重搭工程 |
| 模拟后端/透明素材 | PR [#9](https://github.com/Miqi9880/smart-fitting-mirror/pull/9) 已合并，main 提交 16024b9；35 后端测试，见 backend-validation | haiyangniu191-design 接手维护、复现和 Android 联通；lyf-bee 核对锚点 |
| Android 网络/生成状态 | 本轮提供 HttpFittingApi/FittingSession，12 项单测与 Android HTTP 流程验证见 tryon-validation | haiyangniu191-design 接手复现、异常恢复与真实服务适配，不重复客户端 |
| 衣服叠加 | 本轮提供 TorsoWarp/TryOnView，几何单测与像素绘制测试见 tryon-validation | lyf-bee 接手素材、V01–V07 与真实人体效果验证 |
| 产品页面/端到端 | 本轮提供 TryOnActivity/Model/OwnedImage 首版，模拟闭环有仪器测试；保留诊断入口 | yyyrrrxxx 接手交互修正、大屏装配与异常验收 |
| 真 AI/深度/遮挡 | 未接入，不能用模拟证据替代 | 第 8–9 周确认条件，后续按学期计划推进 |

视觉与后端基线已在 main；本轮新增代码按对应 PR 的合并状态接手，代码链接与提交会同步到 #5/#6/#7。具体步骤见 [联调说明](tryon-integration.md)，结果见 [本轮验证](tryon-validation.md)。未合并时不把分支代码当作 main 已有能力。

## 接手后的责任

1. 不照搬重写已完成部分。读实现与测试，记录自己运行的结果；发现问题用最小复现及小 PR 修正。
2. 代码交接不自动完成整个功能 Issue。网络仍需实测 Android 联通，渲染仍需素材/人体视觉验证，页面仍需完整流程/异常清理，硬件需真实设备证据。
3. 只有明确的整项职责转交才改 Assignee。本次将 app 与集成从 lyf-bee/负责人分给 yyyrrrxxx；后端、渲染、视觉归属保持。
4. 不覆盖他人的未合并工作；公共接口/入口/Gradle/Manifest 改动先同步。在待合并分支接入要写明依赖，正常从最新 main 开始。

验证依据：[Android 记录](android-validation.md)、[后端记录](backend-validation.md)、[无设备场景](no-device-integration.md)。现场事项见 [硬件核对](hardware-intake.md)。
