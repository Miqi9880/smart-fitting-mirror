# 真实试衣调用默认关闭

2026-10-29，Issue #5 第一小PR。真实 Aidge/COS 调用必须显式选择 `--provider aidge-cos` 并额外提供 `--enable-real-provider`。未提供启用开关时，不构造提供方客户端、不读取云端配置，也不创建生成任务；`POST /api/v1/tryon-jobs` 返回现有错误码 `PROVIDER_UNAVAILABLE` 和 HTTP 503。`/health` 报告 `mode=provider-disabled`、`is_mock=false`，避免把禁用误报成 MOCK。

停用状态不改变本地预览；`--provider mock` 仍可独立模拟任务。重复提交同一幂等键在真实调用关闭时会继续得到明确不可用响应，不会生成假的 MOCK 成功，也不会留下任务／幂等记录。已有图片可由本地会话服务持有；禁用门位于外部 COS 上传和 Aidge 提交之前。

## 401 与恢复提示的边界

- 后端游客会话过期／撤销：HTTP `401 SESSION_INVALID`。现有 Android `FittingSession` 收到 `ApiFailure(401)` 后清除当前结果、会话和待生成输入，转到关闭态并提示“服务会话已失效，请重新连接”。用户需手动重连并重新拍摄、主动开始新任务；客户端不自动重放生成。这个既有链路不需要改 `TryOnActivity`。
- Aidge/COS 侧素材 URL 或服务凭证的 401：当前适配会隔离供应商原始异常，统一映射为任务失败 `PROVIDER_UNAVAILABLE`，无法可靠区分“临时素材签名过期”和“服务凭证／权限错误”。恢复真实调用前应由负责人确认需要的错误语义，再决定是否细分错误；本PR不猜测原因，也不加公共字段或自动重提任务。

当创建任务接口本身返回 `503 PROVIDER_UNAVAILABLE` 时，core-network 保留本地原图预览、清除未创建成功的待办并恢复拍摄按钮；不会自动重发旧请求。没有修改 `TryOnActivity`。启用真实调用会产生云端请求及可能的存储／流量费用，因此本PR没有读取密钥、调用真实服务或验证账单。恢复真实调用须由负责人另行批准预算和授权。如负责人确认需要给上游401增加单独提示，再由后续小PR交接错误状态与人工重试行为。

## 本地验证

- `.venv312\Scripts\python.exe -m pytest backend/tests -q`：138 passed；包含缺失 `.env` 路径下 CLI 仍启动禁用态的回归。
- `.venv312\Scripts\python.exe tools/check_contracts.py`：OpenAPI、Schema、fixture、拒绝案例和本地文档链接均通过。
- `android\gradlew.bat :core-network:testDebugUnitTest --tests com.smartfitting.mirror.network.FittingSessionTest.disabledProviderKeepsPreviewAndAllowsOnlyAnExplicitNewCapture`：BUILD SUCCESSFUL。
- `git diff --check`：通过。

测试仅使用本地 FakeProvider／合成数据，不访问真实云服务或读取密钥。
