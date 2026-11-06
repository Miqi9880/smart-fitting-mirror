# 本地模型 HTTP 适配组件首轮

2026-11-01，依据 GitHub `main` 的 #54 更新和[11月2日至8日计划](../weekly-2026-11-02.md)，完成 #5 本周后端小任务：独立适配组件，实现 `TryOnProvider` 接口，暂不注册到 `service.py`、CLI 或默认 MOCK/Aidge 流程。

组件位于 `backend/local_model_provider.py`，复用 `requests` 的 multipart 编码和现有 `GenerationBudget`、`ProviderFailure`、图片上限与 `ProviderResult`，使用标准库直接连接显式配置的 HTTP loopback 端点。健康检查必须 `ready=true`，测试用的 `is_mock=true` 需要显式开启 `allow_mock_for_tests`。当前支持 `tops`，适配器在独立集成 PR 中启用。

POST `/internal/tryon` 发送经过尺寸和解码校验的人物／服装 PNG 或 JPEG 原始字节、`cloth_type=tops` 及剩余预算毫秒数。模型服务负责缩放等预处理。连接超时与同步推理等待分开，健康检查、上传、响应头、响应体和解码共享 `GenerationBudget`。结果在读取和 RGB PNG 转码后均检查字节上限。服务错误映射为 `ProviderFailure`，保留显式的 `retryable` 布尔值。超时会关闭客户端连接，上游 GPU 任务的状态留待真实联调验证。

## 验证

在 Python 3.12 项目虚拟环境运行。

```powershell
.venv312\Scripts\python.exe -m pytest backend/tests/test_local_model_provider.py -q --basetemp=.pytest-tmp-local-model
.venv312\Scripts\python.exe tools/check_contracts.py
.venv312\Scripts\python.exe -m pytest backend/tests -q --basetemp=.pytest-tmp-local-model-full
git diff --check
```

11 月 6 日补充回归。使用合成图像、本机 HTTP stub 和可控连接，验证同步推理等待、慢速响应头和响应体的总时限、窄幅输入原字节、异常错误体、重试标志、最终 PNG 大小及拒绝跳转。真实 FLUX 推理和 GPU 占用释放由后续联调验收。

负责人本机全量后端回归通过 193 项，跳过 1 项，契约检查通过。额外使用本机 HTTP 服务复测，11 秒后返回的有效 PNG 可正常接收，慢速响应在总时限附近断开，公共上传接受的 180×640 图片可传给模型服务，RGB 转码后超过 16 MiB 的结果会被拒绝。
