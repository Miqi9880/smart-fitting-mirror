# 已有基础与四人接手清单

更新：2026-10-19。长期四人分工保持，当前增量改为 [真实服装与真人预览](weekly-2026-10-19.md)，逐人见 [任务卡](tasks.md)。下表保留基础实现交接，不能把先行补位当作全部功能完成。

最新阶段成果：PR #13 无硬件视觉验证、PR #12/#14 渲染报告、PR #17 后端与 Android 网络个人验证均已合并。当前 main 起点为 `43de7ed`；团队共同联调、素材断流、V07 等比例输入与真实人体效果仍待完成。yyyrrrxxx 的个人接手报告/PR 目前未见提交，不将基础页面交付记成其个人验收。

本周实拍原图已放在 [素材目录](materials/README.md)，仍待去背景、标锚点和接入。lyf-bee 负责 `backend/assets/real/` 的图像与来源交付，haiyangniu191-design 负责目录/HTTP 接入；前者不改服务逻辑，后者不并行覆盖未交接的素材文件。Miqi9880 提供真人视觉与 V07 输入，yyyrrrxxx 装配真人入口与调试开关。

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
