# 已有基础与协作者剩余任务

更新日期：2026-09-26。本页根据实际交付拆分任务，保留 haiyangniu191-design 的后端与 AI 职责、lyf-bee 的实时试衣职责。负责人先行补充部分实现，不自动转移整项 Issue 的负责人。

当前交付依据为 [PR #8](https://github.com/Miqi9880/smart-fitting-mirror/pull/8)，实现提交 `0067873`。该 PR 尚未合并；相关代码已经提交并有验证记录，不代表 `main` 已具备这些功能。构建、模拟器检查及真实设备限制见 [Android 验证记录](android-validation.md)。

## 已提供什么，还需要做什么

| 任务与负责人 | Miqi9880 已提供的基础 | 协作者剩余交付 |
| --- | --- | --- |
| [T03 / #4](https://github.com/Miqi9880/smart-fitting-mirror/issues/4)，haiyangniu191-design | OpenAPI 七个操作、状态与幂等约定、素材字段和 JSON 样例；合成原图可用于后续上传联调 | 可运行 HTTP 服务、可访问透明上衣及锚点、成功/失败/延时/限流场景、隔离/取消/过期/清理验证 |
| [T04 / #5](https://github.com/Miqi9880/smart-fitting-mirror/issues/5)，haiyangniu191-design | 可构建的 core-network 模块、FittingApi 与 HTTP DTO、CapturedPhoto 字节与释放约定 | HTTP 客户端、上传、幂等创建与重试、轮询、取消、结果下载、错误状态及结束清理；连接 T03 实测 |
| [T05 / #6](https://github.com/Miqi9880/smart-fitting-mirror/issues/6)，lyf-bee | Android 主工程与诊断页、TryOnRenderer、帧所有权与 FIT_CENTER、可控制的人体场景、原始拍照、摄像头权限和生命周期 | 透明衣服叠加、锚点变换与跟随、partial/lost 隐藏、服装选择/生成/等待/对比页面；接入 T04 后验证完整交互 |
| [T06 / #7](https://github.com/Miqi9880/smart-fitting-mirror/issues/7)，Miqi9880 组织 | 七类合成场景、两种画幅、暂停/单步/重放和 V01–V08 验收步骤 | 待 T03–T05 接入后录制正常流程与异常恢复，验证会话结束及迟到回调清理，再补真实 RGB 和企业设备验证 |

core-network 和 feature-tryon 在上述提交中仍是可构建空模块。诊断页画出的骨架线不等于衣服渲染；原始拍照成功不等于上传成功；视觉源停止不等于远端会话已经删除。

负责人另已开始编写本地模拟后端草稿，但尚未提交或验证，不计为 T03 已完成，也不是协作者已能拉取的交付。准备继续同一服务实现前，先在 #4 确认接手范围；素材、锚点与验收用例可独立推进。草稿可交接时，由负责人补充代码链接和验证结果，再从剩余任务中扣除已验收部分。

## 接入顺序

1. 先审阅 PR #8。通常在合并后从最新 main 开始；需要提前接入时，在自己的 Issue/PR 记录依赖 PR #8 及具体提交，不另建一套 App。
2. haiyangniu191-design 可先整理合法可用的透明上衣与锚点，规划服务验收，并在 core-network 实现现有 FittingApi；与负责人确认本地后端草稿的接手边界。
3. lyf-bee 先用 [无设备联调场景](no-device-integration.md) 实现真实衣服叠加与页面，不重复摄像头、姿态和公共数据类型。
4. 各自提交代码及验证证据后，由负责人组织同一版本的端到端联调。真实 AI、深度遮挡和企业设备性能仍按后续任务处理。

## 以后怎样更新分工

- 原任务负责人、实际实现者、审阅者分别记录；负责人代做某项子任务不等于自动接管整个模块。
- 先行实现或调整范围时，更新原 Issue 的“已有交付、剩余工作、当前实现者、代码链接、验证状态”，让其他成员能继续开发。
- 区分“已有接口或骨架”“正在实现”“已提交待审阅”“已验证”“已合并”。未提交草稿不算可交接成果。
- 只有明确转交整项职责时才更改 Assignee。局部补位保持原 Assignee，并写清文件边界与接手点。
- 不因基础设施已提供就关闭功能 Issue；仍按原验收要求检查相关功能、接入与说明。

其余分支、PR、接口变更与完成规则见 [CONTRIBUTING](../CONTRIBUTING.md)。
