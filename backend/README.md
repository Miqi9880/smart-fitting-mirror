# 智能穿衣镜后端服务

实现 HTTP 合同 V0.4.1（兼容旧试衣路径），含会话、上传、生成任务、六品类初始衣柜及详情/增删改，当前下架连帽款后48件。SQLite 保存内置基础目录，游客的临时编辑隔离保存。默认仍为不联网的 MOCK。选择 `--provider aidge-cos` 但不加 `--enable-real-provider` 时，服务明确拒绝生成，不读云端配置、不创建假成功任务；只有同时提供启用开关才会构造 Aidge/COS 客户端。[当前商品素材与组合预览](../docs/studio-outfits.md)。

2026-10-29视频HTTP升级0.4.1，推荐JSON保持0.4.0兼容。`video_contracts.py`提供共享DTO；目录已有7条Pexels授权参考内容，按[安装说明](assets/videos/README.md)安装本地媒体后可播放。未装包不返回缺文件的候选。公共视频／封面支持GET、HEAD和字节Range，不携带游客凭证。按[正式视频接口](../docs/interfaces/video-recommendation.md)；实现边界与检查见[本地记录](../docs/reports/video-library-route-2026-10-29.md)。COS/Aidge仍按负责人当前决定暂停，不为视频接口启用付费服务。

[`LocalModelProvider`](local_model_provider.py) 将通过校验的 PNG／JPEG 发送给私有回环模型服务，连接和推理共用任务时限。已通过 SSH 转发与真实 FLUX 工作者完成获准样例联调；公共 CLI 现在支持显式选择本地提供方，默认仍为 MOCK。服务启停和实测见 [FLUX 部署记录](../docs/reports/flux-worker-2026-11-06.md)。

显式启动本地模型提供方（需在同一电脑运行模型服务，或先由负责人建立 SSH 本地转发）：

```powershell
.venv312\Scripts\python.exe -m backend --provider local-model --local-model-url http://127.0.0.1:8787
```

命令不读取 Aidge／COS 配置；只支持上衣。默认不带参数时仍使用 MOCK。私有模型调用在匿名统计中标记为 `provider=local-model`、`mode=real`；模型标识和费用仍记为未知。

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

## 本地匿名用量统计

后端默认把每日汇总写入 Git 忽略的 `data/usage-metrics.sqlite3`。统计只保存UTC日期、固定功能／提供方／模式／模型分类、事件次数与总耗时；不保存游客、任务、上传、素材URL、Token或图片标识。幂等重放和状态轮询不另计任务；没有实现的缓存只显示“未实现／未计量”。真实模型SKU暂未从供应商响应取得，记为 `unknown`。

只读查看命令：

```powershell
.venv\Scripts\python -m backend.usage_metrics --db data/usage-metrics.sqlite3
```

报告将模型调用、COS存储、COS流量和请求费用分别标为“未知”；没有账单／费率证据时，未知不代表0元。受控汇总接口仅有草案，当前没有新增公网统计路由、管理页、限额或费用告警，见[接口草案](../docs/interfaces/usage-metrics-draft.md)和[实现记录](../docs/reports/usage-metrics-2026-10-29.md)。

## 显式启用真实 Aidge 提供方

真实模式默认关闭。停用真实服务时使用 `--provider aidge-cos`（不要加启用开关）：`/health` 返回 `mode=provider-disabled`、`is_mock=false`，创建任务返回 `503 PROVIDER_UNAVAILABLE`，提示本地预览仍可使用。默认的 `--provider mock` 可独立运行本地模拟。只有在负责人明确批准预算后，才在本机配置好 Git 忽略的 `.env.cos` 与 `.env.aliyun`，并同时显式启用：

```powershell
.venv312\Scripts\python -m backend --host 127.0.0.1 --port 8000 --provider aidge-cos --enable-real-provider
```

服务保留上传原图，另将人物与上衣等比例缩小、透明背景合成白色、去元数据并编码成符合Aidge范围的JPEG；不拉伸或裁切，不合格输入在两张图均准备完成前不会外传。仅把准备图私有上传COS，30分钟签名URL只在后端内存中交给Aidge。

120秒总时限从公共任务创建开始，采用单调时钟，包含图片准备、上传、提交、查询、下载；公共查询/后台清理及结果提交均检查终态，迟到成功/失败不能覆盖超时。SDK网络等待使用剩余预算，关闭SDK自动重试；只重试已有任务的查询。超时后另给COS删除独立的有界清理预算，PUT响应丢失、HEAD/签名失败也回收本次key，删除失败只记录数量，由一天生命周期兜底。取消或结束立即撤销本地访问；当前上游无取消接口，任务仍可能在云端运行或计费，不把本地超时/删除输入当作云端取消。

真实模式仍只开放清单中标记为可生成的单件上衣。相同幂等键重放返回原任务（含失败终态），不会再次提交；同一时刻只接受一个本地提供方worker，直到其请求和清理结束才释放槽位，忙时返回可重试的 `429 PROVIDER_BUSY`。不建立隐形付费队列；新幂等键是新的显式任务，上游旧任务无法取消，不能保证云端GPU绝无重叠或重复费用。密钥、签名 URL 与供应商原始异常不会进入应用响应或诊断日志。[修复验证与限制](../docs/reports/provider-recovery-2026-10-23.md)。

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

默认目录为六品类51件官网商品，上衣15、长裤15、连衣裙3、半裙6、短裤3、帽子9，[元数据](assets/studio/manifest.json) 入Git，PNG从v3透明运行包安装。当前只有上衣可发起单件生成（默认MOCK，显式真实模式为Aidge），其他预览品类返回 `422 GARMENT_NOT_GENERATABLE`，不生成整套搭配。旧三件合成上衣及白T恤仅在显式 `--catalog-profile regression` 下出现，自动化回归使用独立数据库（如 `--catalog-db data/catalog-regression.sqlite3`），不要与正常演示目录混用。

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
