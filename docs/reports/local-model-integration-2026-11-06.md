# 本地 FLUX 提供方接入现有生成任务

2026-11-06，依据 GitHub 最新 `main`（#72）和[11月9日至15日计划](../weekly-2026-11-09.md)，在 #55 已合并的独立 `LocalModelProvider` 上增加显式 CLI 配置与当前生成会话接入验证。基线为 `5db4a31`。

## 改动

- `python -m backend --provider local-model --local-model-url http://127.0.0.1:8787` 显式启动本地提供方；缺地址、远端地址、默认模式附带地址、或把 Aidge 开关与本地提供方混用都会拒绝。默认启动仍使用 MOCK；未读取 Aidge／COS 配置。
- 提供方继续经 `Store.create_job` 执行，沿用游客鉴权、请求幂等、单任务互斥、总预算、终态遮蔽和结束会话清理。只支持 `tops`。没有静默降级为 MOCK。
- 本地调用在已有匿名统计中标成 `provider=local-model`、`model=unknown`、`mode=real`。提交尝试／成功接收、任务终态均不保存游客、任务、上传或模型任务标识；重复幂等请求和轮询不重复计数。

## Stub 验收

本机 loopback HTTP stub、程序生成图片和临时数据库覆盖：成功的建会话→上传→创建→幂等重放→轮询→PNG下载→结束；服务 503 忙碌后的明确失败且不回退 MOCK；单会话只发生一次真实提供方提交；请求不含游客令牌或本机路径；会话结束后迟到结果不可恢复；超时后只报超时、不显示假成功。单独 CLI 测试确认显式启用端点与默认 MOCK 的边界。未使用真实人像、服务器口令、GPU或云服务。

GitHub #72 已记录负责人先前通过 SSH 转发，以获准 CG 样例对真实 FLUX 工作者完成一次直接 `create_app(provider=LocalModelProvider(...))` 会话联调（4.250 秒、576×864 PNG）。本轮新加的 CLI 路径用本机 stub 验证；没有在此工作站重新登录服务器或再次真实推理。

## 验证

```powershell
.venv312\Scripts\python.exe -m pytest backend/tests/test_local_model_integration.py backend/tests/test_local_model_provider.py backend/tests/test_usage_metrics.py backend/tests/test_tryon_provider.py -q --basetemp=.pytest-tmp-local-integration
# 54 passed
.venv312\Scripts\python.exe -m pytest backend/tests -q --basetemp=.pytest-tmp-local-integration-full
# 204 passed, 1 skipped
.venv312\Scripts\python.exe tools/check_contracts.py
# PASS: OpenAPI 3.0.3, 2 JSON schemas, 21 fixtures, 19 rejection cases, local doc links.
git diff --check
# passed
```

无 Android／镜子／现场验证，也未改变公共 HTTP 0.4.1 或默认服务提供方。需负责人按部署记录启动端点、安排 GPU 窗口，并用同一组获准 CG 样例联测 CLI 启动后的链路；GPU忙碌释放和实际 App 耗时仍分别按服务／镜子责任验收。
