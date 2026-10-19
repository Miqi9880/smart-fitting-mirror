# 智能穿衣镜 · 服饰组

面向 Android 大屏与深度摄像机的课程设计项目，由四人合作开发实时试衣与云端高清试衣功能。

当前版本具备人体视觉、可运行模拟后端、Android 网络状态、透明上衣四点叠加及首版试衣页面，可联调选衣、原图上传、模拟生成、前后对比和结束清理。真实 AI、深度和遮挡尚未接入；硬件型号、SDK 与老师最终验收范围仍需核对。

构建从 [Android 开发说明](android/README.md) 开始；完整操作和成员接入点见 [模拟试衣联调](docs/tryon-integration.md)。默认进入首版试衣页，页底保留硬件诊断入口。

模拟服务从 [后端启动说明](backend/README.md) 开始；[35 项后端验证记录](docs/backend-validation.md) 记录后端证据，Android 闭环另见 [本轮验证](docs/tryon-validation.md)。

## 核心流程

用户进入站位区域 → 选择上衣 → 实时跟随预览 → 点击生成高清试衣图 → 前后对比 → 相关视频推荐 → 结束并清理会话。

实时检测、跟踪和叠加在端侧执行；高清生成由用户主动触发，经后端调用云端服务。

## 课程版本范围

- 单人、正面、固定体验区域，优先支持一类上衣和少量规范素材。
- 接入 RGB 与深度数据，提供人体姿态、分割及站位判断。
- 实现服装定位、缩放、基础形变与遮挡，验证跟随稳定性。
- 实现高清试衣生成、任务状态、失败提示与前后对比。
- 使用服装与风格标签推荐小型视频库中的内容。
- 实现会话清理、摄像头异常处理和基本调用统计。

多件搭配、尺码测量、真实布料仿真及多设备运营暂不纳入课程核心范围。以上是团队拟定的实现范围，最终以老师确认的验收清单为准。

## 四人分工

| 账号 | 角色 | 负责目录与职责 |
| --- | --- | --- |
| Miqi9880 | 项目负责人、硬件与人体视觉 | core-vision、公共接口协调、SDK/设备验证、审阅与合并 |
| haiyangniu191-design | 后端与 AI | backend、core-network；服务、生成状态、结果下载 |
| lyf-bee | 实时试衣效果 | feature-tryon；衣服定位/形变/跟随，后续遮挡 |
| yyyrrrxxx | Android 交互与集成 | app；选衣/生成/对比页面、生命周期装配、完整演示 |

当前按 [10 月 19–25 日任务](docs/weekly-2026-10-19.md) 推进真实服装照片与真人 RGB 预览；[实拍原图和接入说明](docs/materials/README.md) 已准备，功能由后续 PR 实现和验收。先看 [四人任务](docs/tasks.md)、[新成员环境与 Git 入门](docs/getting-started.md) 和 [第 6–18 周计划](docs/semester-plan.md)。第 18 周开始考核，第 14 周前集成确认核心功能，第 16–17 周冻结与演练；学校当前周次待核对。

负责人先行补位的实现及交接点见 [已有基础](docs/task-handoff.md)；成员无需重复开发。[当前设备记录](docs/hardware-status.md) 已于 2026-10-14 更正：负责人报告 ZC-3588A、Android 15、8 GB / 128 GB、触摸可用；有线摄像头已找到但接口尚未匹配，安装权限和相机能力仍待核验。后续按 [现场核对表](docs/hardware-intake.md) 收集实际参数与 SDK。

## 协作资料

- [从这里开始：分工、目录与决策](docs/collaboration.md)
- [四人任务与完成标准](docs/tasks.md)
- [端侧视觉与坐标接口](docs/interfaces/vision.md)
- [后端、素材与任务接口](docs/interfaces/backend.md)
- [OpenAPI 合同](contracts/openapi.json) · [视觉 Schema](contracts/vision.schema.json) · [合成样例](contracts/examples)
- [Git、PR 与接口变更规则](CONTRIBUTING.md)
- [首阶段验证与验收清单](docs/milestones.md)
- [老师参考仓库的迁移清单与来源](docs/reference-reuse.md)

先读自己的任务，再读对应接口。合成样例不含照片或真实 AI 结果；`.invalid` 地址为不可访问的占位地址。

## 协作方式

1. 从最新 `main` 创建个人功能分支，如 `feat/camera-capture`、`feat/tryon-api`、`feat/garment-overlay`。
2. 每次提交聚焦一项可说明的改动，避免同时修改他人负责模块。
3. 通过 Pull Request 合并，说明改动、验证方式及已知限制，邀请至少一位队友审阅。
4. 接口变更先更新约定并通知调用方，尽早联调。

main 已启用保护，仅 Miqi9880 可以通过 PR 合并，禁止直接推送、强推和删除。成员提交个人分支及 PR，具体规则和本轮受托自审例外见 [协作约定](docs/collaboration.md)。

## 数据与配置

- API 密钥只放在后端环境变量或受控配置中，不写入 Android APK 或 Git。
- 不提交用户照片、深度录像、生成结果、真实凭证或企业原始需求文件。
- 需要配置示例时使用 `.env.example`，只包含占位值。
- 演示素材须确认可使用，并在加入素材时注明来源。
- 拍照上传需有明确提示；结束会话时清理未授权留存的数据。
