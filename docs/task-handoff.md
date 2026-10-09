# 已有基础与四人接手清单

更新：2026-10-09。替代 2026-09-26 的三人安排。长期分工见 [协作约定](collaboration.md)，本周逐人任务见 [任务卡](tasks.md)。

| 交付 | 实际状态和证据 | 下一步 / 接手人 |
| --- | --- | --- |
| Android 骨架/类型/视觉/诊断 | PR [#8](https://github.com/Miqi9880/smart-fitting-mirror/pull/8) 已合并，main 提交 52a887a；23 JVM +10 模拟器测试，见 android-validation | Miqi9880 继续真实人体、设备/SDK 核对，不重搭工程 |
| 模拟后端/透明素材 | PR [#9](https://github.com/Miqi9880/smart-fitting-mirror/pull/9) 已合并，main 提交 16024b9；35 后端测试，见 backend-validation | haiyangniu191-design 接手维护、复现和 Android 联通；lyf-bee 核对锚点 |
| Android 网络/生成状态 | 负责人本地已有 HttpFittingApi/FittingSession 草稿及模块测试，尚未发布/合并 | 暂由负责人完成交接；haiyangniu191-design 先复现服务，不重复客户端 |
| 衣服叠加 | 负责人本地已有 TorsoWarp/TryOnView 草稿及几何测试，尚未发布/合并 | 暂由负责人完成交接；lyf-bee 先做素材与 V01–V07 验证准备 |
| 产品页面/端到端 | main 仍为诊断页；完整模拟试衣未交付 | 负责人先补首版闭环，再由 yyyrrrxxx 接手交互修正、装配与验收 |
| 真 AI/深度/遮挡 | 未接入，不能用模拟证据替代 | 第 8–9 周确认条件，后续按学期计划推进 |

前两项已可从 main 拉取；本地草稿不能视作成员已能拿到的代码。上述状态是本次任务发布时快照，负责人后续发布 PR/合并时会更新本页与原 Issue，给出接手提交和验证证据。

## 接手后的责任

1. 不照搬重写已完成部分。读实现与测试，记录自己运行的结果；发现问题用最小复现及小 PR 修正。
2. 代码交接不自动完成整个功能 Issue。网络仍需实测 Android 联通，渲染仍需素材/人体视觉验证，页面仍需完整流程/异常清理，硬件需真实设备证据。
3. 只有明确的整项职责转交才改 Assignee。本次将 app 与集成从 lyf-bee/负责人分给 yyyrrrxxx；后端、渲染、视觉归属保持。
4. 不覆盖他人的未合并工作；公共接口/入口/Gradle/Manifest 改动先同步。在待合并分支接入要写明依赖，正常从最新 main 开始。

验证依据：[Android 记录](android-validation.md)、[后端记录](backend-validation.md)、[无设备场景](no-device-integration.md)。现场事项见 [硬件核对](hardware-intake.md)。
