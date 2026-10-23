# 真实 AI 后端配置与验证教程

本文用于在本地启动“阿里云 Aidge + 腾讯云 COS”的真实虚拟试衣后端。默认启动仍是 MOCK；只有显式加入 `--provider aidge-cos` 才会访问外部服务。

## 0. 先确认边界

- 本教程只验证后端真实链路，不等同于 Android 页面已经完成真实联调。
- 真实调用会消耗 Aidge 额度，并且当前按 QPS 1 一次只允许一个任务在途。请先确认要做一次真实验证，不要反复重跑。
- 不要把 AccessKey、SecretKey、签名 URL、真人照片、生成结果或本地素材 ZIP 提交到 Git，也不要通过聊天截图发送密钥。

## 1. 准备环境

在仓库根目录打开 PowerShell，使用 Python 3.12：

```powershell
python --version
python -m venv .venv312
.venv312\Scripts\python.exe -m pip install -r backend/requirements-dev.txt
```

如需完整商品目录，再安装负责人提供的本地素材包（素材包不进 Git）：

```powershell
.venv312\Scripts\python.exe tools/install_studio_assets.py "本地商品素材包.zip"
```

## 2. 创建两个本机配置文件

两个文件必须放在**仓库根目录**，文件名必须完全相同：

```powershell
Copy-Item .env.aliyun.example .env.aliyun
Copy-Item .env.cos.example .env.cos
```

然后用文本编辑器填写空白项。模板只提供字段，不包含任何真实凭证。

### `.env.aliyun`

填写阿里云 RAM 子用户的 Aidge 凭证：

```dotenv
ALIYUN_ACCESS_KEY_ID=你的阿里云RAM子用户AccessKeyId
ALIYUN_ACCESS_KEY_SECRET=你的阿里云RAM子用户AccessKeySecret
ALIYUN_AIDGE_ENDPOINT=aidge.cn-beijing.aliyuncs.com
ALIYUN_AIDGE_REGION=cn-beijing
ALIYUN_AIDGE_API_VERSION=2026-04-28
```

该子用户只需要 Aidge 的最小权限：`aidge:VirtualTryOn` 和 `aidge:QueryAsyncTaskResult`。不要使用主账号密钥。

### `.env.cos`

填写腾讯云 CAM 子用户的 COS 凭证和项目存储参数：

```dotenv
COS_SECRET_ID=你的腾讯云CAM子用户SecretId
COS_SECRET_KEY=你的腾讯云CAM子用户SecretKey
COS_REGION=ap-guangzhou
COS_BUCKET=smartmirror-tryon-01-1514304052
COS_INPUT_PREFIX=tryon-input/
COS_PRESIGN_EXPIRES_SECONDS=1800
COS_SESSION_TOKEN=
```

COS 桶保持私有。1800 秒是给 Aidge 读取图片的临时签名链接有效期；COS 的 1 天生命周期规则只是异常退出时的兜底清理，不是链接有效期。COS 子用户只允许操作指定桶的 `tryon-input/*` 路径。

## 3. 启动真实模式

先确认两个配置文件存在且没有提交到 Git，然后在第一个终端启动：

```powershell
.venv312\Scripts\python.exe -m backend `
  --host 127.0.0.1 `
  --port 8110 `
  --provider aidge-cos
```

看到服务保持运行即可。不要把 `.env.aliyun` 或 `.env.cos` 的内容复制到终端记录、Issue 或 PR。

## 4. 做一次真实 HTTP 验证

另开第二个终端。准备一张获准使用的标准 CG 模特 PNG（不要使用真人照片），并从已安装目录中选择一件可生成的上衣。下面的 `GARMENT_ID` 只是示例，实际以目录中的 ID 为准：

```powershell
$model = "D:\path\to\approved-standard-model.png"
$garment = "studio_black_tee"
$output = "D:\path\outside\repo\aidge-check.png"

.venv312\Scripts\python.exe -m backend.provider_http_smoke `
  --url http://127.0.0.1:8110 `
  --model-image $model `
  --garment-id $garment `
  --output $output `
  --max-wait-seconds 150
```

成功标准是终端出现 `Real provider HTTP smoke: PASS`，并同时看到 `is_mock: False`、`status: succeeded`，输出文件是一张可读取的 PNG。该脚本会自动创建会话、上传人物图、提交任务、轮询、下载结果并结束会话；不会打印会话令牌。

如果只想检查当前服务是否误启动成 MOCK，可先访问：

```powershell
Invoke-RestMethod http://127.0.0.1:8110/health
```

真实模式应返回 `is_mock` 为 `False`；若为 `True`，先停止服务并重新使用 `--provider aidge-cos` 启动。

## 5. 完成后清理

验证结束后在服务终端按 `Ctrl+C` 停止服务。正常完成的任务会删除 COS 临时输入；异常退出时由 COS 生命周期规则兜底。输出图片放在仓库外的受控目录，不要复制进 Git。

## 常见问题

- `ConfigurationError`：检查两个文件是否在仓库根目录、变量名是否拼写正确、是否误留空白项。
- `429 PROVIDER_BUSY`：已有真实任务在途，等待它结束后再试，不要并行提交。
- `GARMENT_NOT_GENERATABLE`：选择了非上衣或目录中不可生成的条目。
- 返回 `is_mock=true`：服务没有用 `--provider aidge-cos` 启动。
- 结果超时：不要立即重复提交；先保留日志并确认 Aidge 控制台任务状态、网络和额度。

本教程只说明课程开发期的后端 POC，不代表企业商用、自训练模型或真人、多姿态、裤装/裙装质量已经验收。
