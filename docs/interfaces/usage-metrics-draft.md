# 匿名用量与费用汇总接口草案

状态：仅字段草案，尚未实现 HTTP 路由或管理页面。用量首轮只保存在后端主机的日聚合 SQLite 中，通过本机只读命令查看。此草案供负责人评审访问控制、保留期限和费用来源后再决定是否接入。

## 建议查询

`GET /admin/v1/usage-metrics?from_utc=YYYY-MM-DD&to_utc=YYYY-MM-DD`

- 管理员专用身份验证；不可用游客会话令牌访问，不公开给 Android 客户端。
- 只读、无图片上传、无模型调用；限制查询日期跨度和返回行数，并记录管理员查询审计。
- 按UTC日、功能、提供方、模型和 `mock|real` 模式聚合，不返回事件级明细。
- 管理凭据、鉴权方式、保留期限和可见范围须由负责人确认后再实现；当前不得把本草案当作已部署接口。

## 响应字段草案

```json
{
  "period": {"from_utc": "2026-10-29", "to_utc": "2026-10-29"},
  "items": [
    {
      "day_utc": "2026-10-29",
      "feature": "tryon",
      "provider": "aidge-cos",
      "model": "unknown",
      "mode": "real",
      "counts": {
        "task_created": 1,
        "provider_submit_attempt": 1,
        "provider_submit_accepted": 1,
        "succeeded": 1,
        "failed": 0,
        "cancelled": 0
      },
      "cache_reuse": {"count": null, "status": "not_implemented"},
      "duration": {"total_ms": 23500, "sample_count": 1},
      "costs": [
        {"category": "model_inference", "amount": null, "currency": "CNY", "status": "unknown", "unit": null, "rate_date": null, "region": null, "assumption": null},
        {"category": "storage", "amount": null, "currency": "CNY", "status": "unknown", "unit": null, "rate_date": null, "region": null, "assumption": null},
        {"category": "egress_traffic", "amount": null, "currency": "CNY", "status": "unknown", "unit": null, "rate_date": null, "region": null, "assumption": null},
        {"category": "requests", "amount": null, "currency": "CNY", "status": "unknown", "unit": null, "rate_date": null, "region": null, "assumption": null}
      ]
    }
  ]
}
```

## 计数定义与隐私边界

- `task_created` 只在服务接受新任务后增加；同一幂等键重放、轮询、取消查询不增加创建数。
- `provider_submit_attempt` 表示代码已进入提供方提交调用；`provider_submit_accepted` 只在取得供应商成功响应和任务受理标记后增加。响应丢失可能令实际计费与“受理数”不一致，账单仍是费用真值来源。
- 成功、失败、取消各记一次；耗时按任务创建到终态的单调时钟时长累计。缓存没有实现时不产生缓存复用数，报告明确显示“未实现／未计量”。
- 以上是观察到的本地状态计数；未终态就结束／过期移除会话时不补记终态，因此创建数与终态数不保证相等。响应丢失或超过本地期限可能低估上游受理；这些计数不能替代云账单或宣称完整费用统计。
- 首轮只保留日聚合计数和耗时样本总和，不存用户／会话／任务／上传／供应商Task ID、图片、URL、提示词、密钥或逐次时间戳。
- 金额未知时 `amount` 必须为 `null`，不得填0；有核验过的账单或费率后才填写，并同时提供单位、币种、费率日期、地域、假设和来源。模型调用、存储、外网流量、请求费用不得合并成一个总数。

## 首轮本地工具

启动后端时默认每日汇总文件为 `data/usage-metrics.sqlite3`（Git 忽略）。本机只读报告：

```powershell
.venv\Scripts\python -m backend.usage_metrics --db data/usage-metrics.sqlite3
```

管理接口、管理页面、日／月硬限额和费用告警均不在这一步实现范围内。
