# 本地模拟试衣服务

实现 HTTP 合同 V0.3.0，含原会话/上传/生成操作、六品类51件初始衣柜及详情/增删改。SQLite 保存内置基础目录，游客的临时编辑隔离保存。所有生成任务仍返回 `is_mock=true`；输出是静态叠加素材并标明 MOCK 的图片，没有接入真实 AI 或付费服务。[当前商品素材与组合预览](../docs/studio-outfits.md)。

## 启动

在仓库根目录使用 Python 3.12：

```powershell
python -m venv .venv
.venv\Scripts\python -m pip install -r backend/requirements-dev.txt
.venv\Scripts\python tools/install_studio_assets.py <本地商品素材包.zip>
.venv\Scripts\python -m backend --host 127.0.0.1 --port 8000 --mode success
```

macOS/Linux 将 `.venv\Scripts\python` 换成 `.venv/bin/python`。仅运行服务可安装 `backend/requirements.txt`；测试和烟雾检查使用 requirements-dev。无需任何供应商密钥或 GitHub 令牌。

健康检查 `GET http://127.0.0.1:8000/health`，业务前缀 `/api/v1`。Android 模拟器通过 `http://10.0.2.2:8000` 访问主机；真实设备地址和企业网络需另行配置。该命令只绑定本机，适合开发联调。

会话、游客编辑、上传图片与结果为单进程内存实现，重启会清空；基础服装元数据默认持久化在 `data/catalog.sqlite3`，可用 `--catalog-db` 指定独立路径。固定会话时长 30 分钟，后台每秒检查到期和模拟任务；显式结束会立即撤销资源访问。临时 multipart 文件由解析器管理并在请求结束时关闭，图片与结果不写入目录数据库。清理表示移除应用持有的记录，不声称物理内存已安全擦除。

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

默认目录为三款官网平铺上衣、长裤和针织帽，[元数据](assets/studio/manifest.json) 入 Git，PNG 从本地素材包安装。裤子与帽子仅支持实时预览，创建生成任务返回 `422 GARMENT_NOT_GENERATABLE`；上衣生成仍为单件 MOCK，不生成整套搭配。旧三件合成上衣及白 T 恤仅在显式 `--catalog-profile regression` 下出现，自动化回归使用独立数据库（如 `--catalog-db data/catalog-regression.sqlite3`），不要与正常演示目录混用。

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

Android 对本服务的首版接入见 [模拟试衣联调](../docs/tryon-integration.md) 与 [验证记录](../docs/tryon-validation.md)。真实人像精度、企业设备性能和长时间运行仍需验证。TestClient 的 httpx 兼容层会提示弃用警告，现有固定版本下测试可运行；真实 HTTP 测试独立覆盖 CLI 服务。
