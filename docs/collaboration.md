# 四人协作约定

更新：2026-10-09，第 6 周。总目标和第 18 周考核安排见 [学期计划](semester-plan.md)。

| 维护人 | 文件边界 | 对其他成员交付 |
| --- | --- | --- |
| Miqi9880 | android/core-vision、协调 core-contracts 和共享配置 | 同帧 RGB/关键点、原始拍照、真实硬件能力、公共接口 |
| haiyangniu191-design | backend、android/core-network | HTTP 服务、服装目录、生成状态、结果下载和清理 |
| lyf-bee | android/feature-tryon | 衣服叠加 View/Renderer、素材锚点与形变、后续遮挡 |
| yyyrrrxxx | android/app | 交互页面、模块装配、前后对比、生命周期集成与演示 |

Miqi9880 组织跨模块联调、审查公共接口并合并 PR。每人负责本模块测试与说明。负责人先行实现部分基础属于补位，不能据此认定整项职责已转移；当前实现状态见 [交接清单](task-handoff.md)。

## 约定与交接

- 图像、关键点采用竖正且未镜像的原图归一化坐标；显示统一镜像一次。帧异步使用先 retain，完成 release；最新帧替换与清理遵守共享接口。
- 网络负责人不写整套产品 UI，渲染负责人不重开摄像头，页面负责人不重复实现 HTTP/采集。公共接口改动先说明调用方和兼容性。
- 深度、mask、距离未真实可用时保持 null/unknown；没有深度仍应能演示。普通人体分割不等同手臂/躯干分割。
- 工程方向为 Kotlin/Android；后端当前 Python 3.12 + FastAPI 模拟服务。工具版本见 Android/后端 README。厂商 SDK 与真实 AI 提供方需实证确认。
- 各自从最新 main 开功能分支；每周一次同版本联调，小 PR 尽早提交。第一周按 [任务](tasks.md) 做一个可验证增量。

## 主分支保护

2026-10-09 已通过 GitHub API 确认 [main-owner-merge](https://github.com/Miqi9880/smart-fitting-mirror/rules/24785240) 为 Active，目标 refs/heads/main。禁止删除/强推，更新受限并要求 PR、1 人审批、解决审阅讨论；新提交撤销旧审批。仅 Miqi9880 有 PR 范围的例外权限，因此只有负责人能合并，个人功能分支不受该规则限制。

负责人明确授权本轮 Codex 工作在适用检查通过、自己审查并记录证据后合并自己的 PR；这是本轮受托工作的例外，不代表成员可以自行合并，也不应假称 GitHub 已有他人批准。普通成员 PR 仍由负责人审阅。技术上负责人可在 PR 中使用例外权限，因此检查是否通过仍须实际核对。

规则尚未强制指定 CI 状态名称：现有工作流按文件路径触发，硬绑不运行的检查会使文档 PR 永久等待。相关构建/测试通过是团队合并要求。更多分支、接口和提交规则见 [CONTRIBUTING](../CONTRIBUTING.md)。
