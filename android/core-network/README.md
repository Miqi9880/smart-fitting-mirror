# 后端同学接入位置

维护人：haiyangniu191-design。当前仅是可构建模块骨架，未实现网络请求。

Miqi9880 已提供 FittingApi、HTTP DTO 和 CapturedPhoto，无需重复定义。下一步实现 HTTP 客户端与生成状态流程，详见 [T04 接手清单](../../docs/task-handoff.md)。原始拍照已实现，上传、远端取消和会话清理仍需本模块接入并验证。

从 core-contracts 的 FittingApi 接口与 HTTP DTO 开始，实现会话、目录、上传、幂等创建、查询、取消和清理。不要修改摄像头或诊断页面。接口实现可采用你选择的网络库，依赖变更在 PR 说明。
