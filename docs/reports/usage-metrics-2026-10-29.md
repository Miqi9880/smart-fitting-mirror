# 匿名调用与费用统计首轮

2026-10-29，Issue #5 第二个小PR。后端启动时把事件聚合到 Git 忽略的 `data/usage-metrics.sqlite3`，按UTC日、功能、提供方、模式和模型标识记录任务创建、提供方提交尝试／受理、任务成功／失败／取消及耗时。存储仅有聚合计数和耗时总和／样本数；不会保存游客、任务、上传、上游Task ID、图片、URL、Token或提示词。

同一幂等键重放在创建计数之前返回原任务，因此不会重复计数；轮询不会产生新事件，终态只记录一次。提交尝试在进入Aidge提交调用前计数，受理数只在供应商返回成功及任务标识后计数；响应丢失时，受理数可能低于实际计费，账单仍是费用真值来源。当前没有缓存实现，不写入缓存复用记录，报告明确显示“未实现／未计量”。真实底层模型SKU未从当前提供方响应取得，真实模式模型维度记为 `unknown`。

`python -m backend.usage_metrics --db data/usage-metrics.sqlite3` 以 SQLite 只读模式展示本机汇总。模型调用、COS存储、COS流量和请求费用分别显示“未知”；缺少账单／费率时不填0元。管理查询接口仅有[字段与访问控制草案](../interfaces/usage-metrics-draft.md)，本轮不新增HTTP管理路由、管理页、硬限额或告警。

没有读取密钥或访问真实云服务；费用数值和真实账单匹配仍未验证。

## 本地验证

- `.venv312\Scripts\python.exe -m pytest backend/tests -q --basetemp=.pytest-tmp-submitted-final`：调用开关和统计分支堆叠后的全套测试 143 passed；烟雾测试使用临时统计库，不污染默认文件。
- `.venv312\Scripts\python.exe tools/check_contracts.py`：OpenAPI、Schema、fixture、拒绝案例和本地文档链接通过。
- `.venv312\Scripts\python.exe -m backend.usage_metrics --db data/usage-metrics.sqlite3`：只读报告可运行；无数据库时输出“暂无记录”，不会创建数据库。
- `git diff --check`：通过。
