# 本地模拟试衣服务

实现 V0.1 的七个 HTTP 操作，供 Android 网络和页面联调。所有任务均返回 `is_mock=true`；输出是在合成输入上静态叠加演示上衣并标明 MOCK 的图片，没有接入真实 AI、人体换装算法或付费服务。

## 启动

在仓库根目录使用 Python 3.12：

```powershell
python -m venv .venv
.venv\Scripts\python -m pip install -r backend/requirements-dev.txt
.venv\Scripts\python -m backend --host 127.0.0.1 --port 8000 --mode success
```

macOS/Linux 将 `.venv\Scripts\python` 换成 `.venv/bin/python`。仅运行服务可安装 `backend/requirements.txt`；测试和烟雾检查使用 requirements-dev。无需任何供应商密钥或 GitHub 令牌。

健康检查 `GET http://127.0.0.1:8000/health`，业务前缀 `/api/v1`。Android 模拟器通过 `http://10.0.2.2:8000` 访问主机；真实设备地址和企业网络需另行配置。该命令只绑定本机，适合开发联调。

服务为单进程内存实现，重启会清空会话。固定会话时长 30 分钟，后台每秒检查到期和模拟任务；显式结束会立即撤销资源访问。临时 multipart 文件由解析器管理并在请求结束时关闭，图片与结果只保存在进程内存。清理表示移除应用持有的记录，不声称物理内存已安全擦除。

## 可重复的模拟场景

| `--mode` | 行为 | 检查方式 |
| --- | --- | --- |
| success | queued 后进入 running，约 2 秒成功 | 默认烟雾检查 |
| failure | 约 2 秒返回 failed / MOCK_GENERATION_FAILED | 烟雾检查加 `--expect failed` |
| delayed | 约 15 秒成功 | 同 success，便于观察等待或手动取消 |
| timeout | 约 120 秒返回 failed / GENERATION_TIMEOUT | 烟雾检查加 `--expect failed`；单元测试用可控时钟验证边界 |
| rate-limit | 每个新幂等键首次创建返回 429、Retry-After: 1；原键重试可创建 | 默认烟雾检查自动用原键重试 |

改变场景需停止并重启服务；旧会话随进程结束失效。场景模式只在本地 CLI 配置，业务 API 不提供任意注入生成结果的入口。

## 协作者接入

按 [HTTP 合同](../contracts/openapi.json) 和 [行为约定](../docs/interfaces/backend.md) 调用。创建会话后，在目录、上传、任务、素材图和结果图请求中都携带服务返回的 `Authorization: Bearer ...`。图片地址也受会话授权保护；不要把授权头转发到不同来源的 URL。

三件透明演示上衣及锚点见 [素材说明](assets/README.md)。绿色上衣只能预览，创建生成任务返回 `422 GARMENT_NOT_GENERATABLE`。真实 AI 参考图与真实商品素材仍由后端负责人后续确认。

上传限制为 JPEG/PNG、原始文件不超过 10 MiB、解码像素不超过 1600 万；读取 EXIF 方向后转成竖正像素并去除元数据。标准化 PNG 可能大于输入文件，按内存容量管理。资源上限：100 个会话、每会话 8 次保留上传/30 个任务、输入与结果总计 128 MiB（不含解码和框架临时内存）。每分钟同 IP 创建会话 20 次、每会话保存上传 10 次、创建新任务尝试 6 次；已成功绑定的幂等重放不消耗新建配额。

同 key 同输入只生成一个任务，不同输入返回 409。取消与完成遵守最先提交的终态；取消后不会再产生结果。其他会话的上传/任务/结果返回 404，过期或撤销令牌返回 401；同一已撤销令牌重复 DELETE 返回 204。任意远程图片 URL 不作为生成输入。

同时最多处理 4 个上传请求，超过时在读入图片前返回带 Retry-After 的 429，避免无限并发解码。所有应用错误采用合同规定的 error 对象。

## 验证

```powershell
.venv\Scripts\python tools/check_contracts.py
.venv\Scripts\python -m pytest backend/tests -q
```

测试包含合同响应校验、并发幂等重试、取消竞争、隔离和撤销、空闲到期清理、上传期间结束会话、EXIF 去除、文件/像素边界、非法 multipart，以及 CLI 子进程通过真实回环 HTTP 运行成功/失败/限流流程。所有输入均为测试合成图。

服务启动后，在另一个终端运行：

```powershell
.venv\Scripts\python -m backend.smoke --url http://127.0.0.1:8000
```

烟雾检查走创建会话→目录/素材→上传→幂等创建→轮询→结果下载→跨会话拒绝→取消另一任务→结束/重复结束/资源撤销。默认只输出不含令牌的摘要；可用 `--output generated-results/mock-preview.png` 保存测试合成结果，目录被 Git 忽略。

当前仍需 Android 实际接入、真实人像精度、企业设备性能和长时间运行验证。TestClient 的 httpx 兼容层会提示弃用警告，现有固定版本下测试可运行；真实 HTTP 测试独立覆盖 CLI 服务。
