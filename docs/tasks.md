# 四人当前任务（第 6 周）

整体计划见 [第 6–18 周路线](semester-plan.md)；第一次配置请读 [环境与 Git/PR 入门](getting-started.md)。未来一周每人预计 6–8 小时，以跑通环境、理解基线、完成一个小 PR/实测报告为主；第 7 周完成模拟闭环，不要求新成员本周从零写整套系统。

2026-10-13 更新：Miqi9880 本周现场硬件验证暂缓，改为无硬件视觉验证与模拟联调；具体安排以 [Issue #3](https://github.com/Miqi9880/smart-fitting-mirror/issues/3) 为准。[本次独立验证与启动步骤](reports/Miqi9880-2026-10-13.md) 已交付，团队共同验收和硬件待办仍保留。其他三人的职责不变。

| 成员 | 长期归属 | 下一周最小交付 | 详细任务 |
| --- | --- | --- | --- |
| Miqi9880 | 硬件、人体视觉、公共接口、项目协调 | 无硬件视觉/原图/清理验证、模拟闭环与交接报告（10 月 13–18 日调整） | [#3](https://github.com/Miqi9880/smart-fitting-mirror/issues/3) |
| haiyangniu191-design | 后端、AI、Android 网络 | 三种模拟服务复现、调用链与网络接手记录 | [#5](https://github.com/Miqi9880/smart-fitting-mirror/issues/5) |
| lyf-bee | 实时服装渲染 | 一件 PNG 锚点检查、V01–V07 场景验证/准备 | [#6](https://github.com/Miqi9880/smart-fitting-mirror/issues/6) |
| yyyrrrxxx | Android 页面、模块装配与交互验收 | 运行 App、页面状态表、正常/异常验收步骤 | [#7](https://github.com/Miqi9880/smart-fitting-mirror/issues/7) |

这些是长期功能卡内的本周增量；本周报告通过不等于整个功能完成。每张 Issue 包含已有交付、文件范围、逐日任务、环境命令、验收标准和依赖。进度在原卡更新，避免重复认领。

## 已完成的基线

- T01 / [#2](https://github.com/Miqi9880/smart-fitting-mirror/issues/2)：Android 骨架与公共类型，PR #8 已合并。
- T03 / [#4](https://github.com/Miqi9880/smart-fitting-mirror/issues/4)：模拟 HTTP 服务与演示素材，PR #9 已合并、35 项测试通过；后续维护仍由 haiyangniu191-design 负责。
- T02 的 RGB 首版和模拟器验证已交付，真实人体、企业设备和深度另行验收；所以 #3 保持打开。

## 本次分工变化

原 T05 中 app 页面/选衣/生成/对比/主工程集成转给 yyyrrrxxx；lyf-bee 保留 feature-tryon。#7 改由 yyyrrrxxx 负责集成实现，Miqi9880 仍组织协调。后端与 core-network 保持 haiyangniu191-design。

负责人先行补充了网络、渲染和首版 App 流程；交接 PR/提交与验证证据会同步到原 Issue，成员先做本周环境/复现，再基于交付代码修正，不重复搭建。见 [当前交接清单](task-handoff.md) 和 [联调说明](tryon-integration.md)。
