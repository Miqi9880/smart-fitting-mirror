# 四人当前任务（2026-10-19 至 10-25）

本周按负责人新要求推进 **真实服装照片＋真实人体输入**。完整总目标、完成状态、先后依赖、环境与逐项验收见 [10 月 19–25 日任务](weekly-2026-10-19.md)；素材从 [真实输入材料](materials/README.md) 开始。首次配置请读 [环境与 Git/PR 入门](getting-started.md)，长期里程碑见 [第 6–18 周路线](semester-plan.md)。不根据日期推定学校周次。

原模拟闭环、视觉回归、lyf-bee 的场景报告及 haiyangniu191-design 的网络报告已分别通过 PR #11/#13/#12/#14/#17 交付。它们可继续用于回归，本周验收改为一件实拍服装在实时真人画面上跟随；旧绘制上衣与合成点不计为本周效果。现有 APK 仍是原模拟联调版，本次任务发布和原图准备不表示已实现新功能。

历史进展（2026-10-13）：Miqi9880 于 10 月 13–18 日暂缓现场验证，已交付 [无硬件视觉验证与启动步骤](reports/Miqi9880-2026-10-13.md)。共同验收和硬件待办转入当前 [Issue #3](https://github.com/Miqi9880/smart-fitting-mirror/issues/3)，不将旧的暂缓安排扩展成无限期停止真人工作。

2026-10-14 更正：[当前设备](hardware-status.md) 为负责人报告的 ZC-3588A / Android 15，已能操作界面，有线摄像头已找到但接口不匹配。此前 S40、电源/不开机/摄像头未找到的信息不再用于任务决策。本次仅补充设备事实，不把安装、RGB 或深度验证记为完成。

| 成员 | 长期归属 | 下一周最小交付 | 详细任务 |
| --- | --- | --- | --- |
| Miqi9880 | 硬件、人体视觉、公共接口、项目协调 | 真实 RGB/关键点实测、V07 等比例输入，5–7 小时 | [#3](https://github.com/Miqi9880/smart-fitting-mirror/issues/3) |
| haiyangniu191-design | 后端、AI、Android 网络 | 真实素材目录接入与断流排查，5–7 小时 | [#5](https://github.com/Miqi9880/smart-fitting-mirror/issues/5) |
| lyf-bee | 实时服装渲染 | 一件实拍上衣抠图/锚点/跟随及调试点显示，6–8 小时 | [#6](https://github.com/Miqi9880/smart-fitting-mirror/issues/6) |
| yyyrrrxxx | Android 页面、模块装配与交互验收 | 真人入口/关键点开关及完整联调，5–7 小时 | [#7](https://github.com/Miqi9880/smart-fitting-mirror/issues/7) |

这些是长期功能卡内的本周增量；工时含成员理解、实现和验证，不是代理执行时间。每张 Issue 包含已有交付、文件范围、环境命令、验收标准和依赖。先交一个可接入的小 PR，周末再共同验收；本周报告通过不等于长期功能卡完成。

## 已完成的基线

- T01 / [#2](https://github.com/Miqi9880/smart-fitting-mirror/issues/2)：Android 骨架与公共类型，PR #8 已合并。
- T03 / [#4](https://github.com/Miqi9880/smart-fitting-mirror/issues/4)：模拟 HTTP 服务与演示素材，PR #9 已合并、35 项测试通过；后续维护仍由 haiyangniu191-design 负责。
- T02 的 RGB 首版和模拟器验证已交付，真实人体、企业设备和深度另行验收；所以 #3 保持打开。

## 本次分工变化

原 T05 中 app 页面/选衣/生成/对比/主工程集成转给 yyyrrrxxx；lyf-bee 保留 feature-tryon。#7 改由 yyyrrrxxx 负责集成实现，Miqi9880 仍组织协调。后端与 core-network 保持 haiyangniu191-design。

负责人先行补充了网络、渲染和首版 App 流程；交接 PR/提交与验证证据会同步到原 Issue，成员先做本周环境/复现，再基于交付代码修正，不重复搭建。见 [当前交接清单](task-handoff.md) 和 [联调说明](tryon-integration.md)。
