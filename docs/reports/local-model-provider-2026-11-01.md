# 本地模型 HTTP 适配组件首轮

2026-11-01，依据 GitHub `main` 的 #54 更新和[11月2日至8日计划](../weekly-2026-11-02.md)，完成 #5 本周后端小任务：独立适配组件，实现 `TryOnProvider` 接口，暂不注册到 `service.py`、CLI 或默认 MOCK/Aidge 流程。

组件位于 `backend/local_model_provider.py`，复用运行依赖 `requests`、现有 `GenerationBudget`、`ProviderFailure`、`prepare_input` 与 `ProviderResult`。默认无服务地址；显式配置时只接受 HTTP loopback origin（包括本地 SSH 转发端口），不读取用户请求中的地址、不使用环境代理、不跟随重定向。当前仅支持 `tops`。健康检查必须 `ready=true`；生产默认拒绝 `is_mock=true`，仅构造时显式设置 `allow_mock_for_tests=True` 才能用于 fake 测试。

POST `/internal/tryon` 只发送预处理后的人物／服装 JPEG 字节、`cloth_type=tops` 和同一预算的剩余毫秒数；不发送本机路径、游客令牌或凭证。健康检查、上传等待、结果读取、解码共享 `GenerationBudget`，POST不自动重试。以有界读取和PNG／像素校验构造 `ProviderResult`；将服务错误映射为现有 `ProviderFailure` 类别和安全短消息。超时只表示调用端停止等待，不表示上游GPU任务已取消。

## 验证

在 Python 3.12 项目虚拟环境运行：

```powershell
.venv312\Scripts\python.exe -m pytest backend/tests/test_local_model_provider.py -q --basetemp=.pytest-tmp-local-model
# 23 passed
.venv312\Scripts\python.exe tools/check_contracts.py
# PASS: OpenAPI 3.0.3, 2 JSON schemas, 21 fixtures, 19 rejection cases, local doc links.
.venv312\Scripts\python.exe -m pytest backend/tests -q --basetemp=.pytest-tmp-local-model-full
# 184 passed, 1 skipped
git diff --check
# passed
```

测试以注入的本地 fake HTTP Adapter 和程序生成图片覆盖默认未配置、loopback限制、健康未就绪／is_mock限制、正常PNG、仅上衣、输入超限／无效、服务错误码、非PNG／超大／坏尺寸结果、超时、迟到结果及拒绝跳转。没有下载权重、连GPU／模型服务、访问云端、修改公共合同／CLI／`service.py` 或调用真实人像。真实模型推理、实际GPU取消与资源释放留给负责人部署后的独立联调验收。
