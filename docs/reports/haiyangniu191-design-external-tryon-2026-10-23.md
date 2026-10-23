# haiyangniu191-design 外部虚拟试衣 API 接入报告

本报告保留原始提交9dc4a74的80项测试与两次CG输入真实调用。随后PR审查的三个问题由负责人明确接手，补单调时钟总预算、部分上传清理和生成输入适配，106项软件检查见[接手修复记录](provider-recovery-2026-10-23.md)。原始实测不能作为修复后再次真实调用的证据；下方“上游单并发”以最新记录的本地worker边界为准。

- 执行日期：2026-10-22 至 2026-10-23
- 对应任务：[Issue #5](https://github.com/Miqi9880/smart-fitting-mirror/issues/5)
- 分支：`feat/hyl-external-tryon-api`
- 开始基线：`be0d3a9275dac8208ee9dac2b9f28baab4b95cdf`
- 范围：外部服务选型、获准合成输入真实调用、现有后端任务接口适配、异常边界和清理验证

## 本周结论

本周已完成一个真实外部虚拟试衣提供方的条件确认、最小权限配置、两次获准静态输入调用，以及现有 HTTP 0.3.0 后端任务接口的适配。最终方案使用阿里云 Aidge 生成，腾讯云 COS 仅保存短期输入；后端默认仍为不联网的 MOCK，只有启动时显式选择 `aidge-cos` 才会访问真实服务。

真实结果已分别通过独立编排器和 App 共用的公共 HTTP 接口取得。后者走完创建临时会话、上传人物图、按 `garment_id` 创建任务、轮询、受保护结果下载和结束会话，返回 `is_mock=false`。本轮没有修改 Android 公共合同；Android 客户端已有的 `is_mock` 分支可区分模拟与真实结果，但尚未在 Android 页面人工执行真实 Aidge 任务，因此不把后端实测写成 App 联调通过。

## 方案与数据边界

```text
Android App／HTTP 客户端
  → FastAPI /uploads、/tryon-jobs、任务轮询、受保护结果下载
  → 腾讯云 COS 私有 Bucket：两张临时输入
  → 30 分钟签名 HTTPS URL（仅在后端内存中）
  → 阿里云 Aidge VirtualTryOn／QueryAsyncTaskResult
  → 后端下载并规范化结果
  → 删除 COS 输入；Bucket 1 天生命周期规则兜底
```

- Aidge 要求人物图与服装图具有可读取的 HTTP(S) URL。COS 与 Aidge 可来自不同厂商，Aidge 不要求必须使用阿里云 OSS。
- COS Bucket 保持私有；签名链接有效期为 1800 秒。1 天生命周期是异常退出后的兜底清理，不是链接有效期。
- 阿里云 RAM 子用户只授权 `aidge:VirtualTryOn` 与 `aidge:QueryAsyncTaskResult`。腾讯云 CAM 子用户只可操作指定 Bucket 的 `tryon-input/*`。
- `.env.aliyun`、`.env.cos`、任务状态、签名 URL、真实密钥和生成结果均留在本机 Git 管理范围外。
- 两次真实调用均使用同一张非真人标准 CG 模特和本地课程素材包中的单件上衣；未上传真人照片。商品 PNG 没有公开再分发许可声明，不进入 Git。

## 提供方选择记录

火山引擎图片换装曾完成一次获准合成输入 POC，但连衣裙被生成成近似长上衣，未满足本项目对服装类别的基本预期，因此停止后续真实调用。阿里云 Aidge 的公开品类与成本更贴近本项目，并在本次两件上衣测试中产生可辨认的领型／帽型、袖口、下摆和门襟。

存储最初考虑阿里云 OSS；当时账号尚未开通 OSS，且可能产生存储、请求与公网流量费用。用户的腾讯云账号已有 COS 免费标准存储额度，因此改用 COS 做开发期临时输入存储。后端把“上传、短时签名、删除”封装在存储边界中，后续仍可替换为学校存储或其他合规实现。

## 真实验证

| 链路 | 人物输入 | 服装输入 | 结果 | 端到端时间 | 结果文件处理 |
| --- | --- | --- | --- | --- | --- |
| 独立 COS→Aidge 编排 | 非真人标准 CG 模特 | 藏青短袖 Polo，`tops` | `832×1248` PNG，任务成功 | 约 6 分 23 秒；期间曾因缺少查询权限暂停，不能代表正常生成速度 | 仓库外私有保存，不提交 Git |
| 公共 HTTP `/tryon-jobs` | 非真人标准 CG 模特 | 灰色拉链连帽卫衣，`upper_body`→`tops` | `is_mock=false`，`succeeded`，`832×1248` PNG | 约 69 秒，含服务启动、会话、COS 上传、生成、轮询与下载 | 仓库外私有保存，不提交 Git |

视觉检查范围只包括两张合成结果：Polo 的领口、门襟、短袖和下摆完整；连帽卫衣的帽型、抽绳、全长拉链、口袋、长袖和袖口完整。人物面部、双手、裤子、鞋和背景保持稳定。两张结果只能证明这两个输入样例成功，不能外推为其他人物、姿态、服装类别或企业质量验收。

两次任务预计共消耗 2 张 Aidge 虚拟试衣额度。实际剩余额度应以阿里云控制台为准。本报告不记录 TaskId、对象键、签名 URL、AccessKey 或 SecretKey。

## 后端实现

- `backend/tryon_provider.py` 定义真实提供方边界；上传标准化人物 PNG 和所选本地服装素材，提交／查询 Aidge，下载并验证结果，在终态或 120 秒时限后清理 COS 输入。
- `backend/service.py` 复用既有会话、上传、目录、任务、结果与鉴权接口。真实调用在后台线程运行，不持有公共会话锁；默认 MOCK 行为保持。
- 同一幂等键重放返回原任务，不重复提交。当前 Aidge 默认 QPS 1，因此全局只允许一个上游任务在途；忙时返回可重试的 `429 PROVIDER_BUSY`，不建立隐形付费队列。
- App 取消或结束会话立即撤销本地结果访问。Aidge 当前接入没有取消操作，已提交的上游任务仍查询到终态／时限以便清理；迟到结果被丢弃。
- 查询出现一次临时异常时继续查询，不提前删除输入。COS 删除失败只记录失败数量，不记录对象键或签名 URL，并由 1 天生命周期规则兜底。
- `backend/provider_http_smoke.py` 只允许连接 `is_mock=false` 的后端，使用一个稳定幂等键完成一次真实 HTTP 闭环，且不输出会话令牌。

## 验证结果

在 Python 3.12.14、Git 忽略的 `.venv312` 中执行：

```powershell
.venv312\Scripts\python.exe -m pytest backend/tests -q
.venv312\Scripts\python.exe -m compileall -q backend
.venv312\Scripts\python.exe tools/check_contracts.py
git diff --check
```

结果：

- 后端测试：**80 passed**。
- OpenAPI：3.0.3；2 个 JSON Schema、17 个 fixtures、8 个拒绝用例通过。
- `compileall` 与 `git diff --check` 通过。
- 新增测试使用合成图片和假 COS／Aidge 客户端，覆盖真实提供方成功、失败、幂等、单并发、取消后拒绝迟到结果、查询异常重试、清理失败安全告警，以及 HTTP 烟雾检查器拒绝 MOCK。
- 真实 HTTP 烟雾检查只执行一次创建任务；没有用自动化测试反复消耗云端额度。

## 当前未完成与交接

1. 尚未在 Android App／模拟器中人工选择服装并触发真实 Aidge 任务；同一 App 的真实结果展示仍需一次联调，不在本报告中标记通过。
2. 真实提供方的失败、超时、取消、会话结束和迟到结果已完成本地受控响应验证，但没有为了制造异常而反复消耗真实额度；对应里程碑继续保持未完成。
3. 尚未用真人、目标镜子、多姿态、多体型、裤装、裙装或帽子验证 Aidge 质量。现场／真人任务继续由负责人按现有分工执行。
4. Aidge 与 COS 是课程 POC 的外部服务，不是自训练模型，也不构成企业可商用模型交付。开源模型训练／微调是独立探索，不是本分支的完成条件。
5. 提交时只包含代码、测试、空白配置模板和本文档；不得加入 `.env*` 实际配置、`private/`、商品 PNG、素材 ZIP、任务状态或生成结果。

## 官方资料

- 阿里云 Aidge VirtualTryOn：<https://help.aliyun.com/zh/aidge/api-aidge-2026-04-28-virtualtryon>
- 阿里云 Aidge QueryAsyncTaskResult：<https://help.aliyun.com/zh/aidge/api-aidge-2026-04-28-queryasynctaskresult>
- 腾讯云 COS Python SDK：<https://cloud.tencent.com/document/product/436/12269>
