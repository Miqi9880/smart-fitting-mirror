# FLUX 上衣生成服务部署与验证

2026-11-06，老师服务器已安装单 GPU 推理进程，部署目录为 `/data_new/smart-fitting-mirror/src/flux-worker-20261106`。权重版本为 `e7b7dc27f91deacad38e78976d1f2b499d76a294`，使用 Python 3.10、PyTorch 2.8 + CUDA 12.8、Diffusers 0.37.1、BF16、4 步、固定种子 42。沿用扩展对照的提示词。输入是获准 CG 和服装样例；预处理校验 PNG／JPEG、统一方向、透明边缘合到白底。输出最长边 864，按人物图比例对齐到 16 像素网格。

## 启动与停止

从项目根运行以下命令。复部署时，将当前仓库的 `backend/__init__.py`、`backend/provider_control.py`、`backend/provider_images.py`、`backend/flux_worker.py` 和 `backend/requirements-flux-worker.txt` 同步到发布目录的 `backend/`，核对文件哈希。服务环境已有本地模型依赖；仅在补装传输依赖时运行 `"$py" -m pip install -r backend/requirements-flux-worker.txt`。

```bash
source /data_new/smart-fitting-mirror/src/environment/activate.sh
cd "$SFM_ROOT/src/flux-worker-20261106"
umask 077
py="$SFM_ROOT/envs/flux2-klein4b-py310/bin/python"
export OMP_NUM_THREADS=4 MKL_NUM_THREADS=4

# 按本次约定检查物理 1 号卡空闲后才启动。共享锁与此前评估脚本相同。
export CUDA_VISIBLE_DEVICES=$(nvidia-smi -i 1 --query-gpu=uuid --format=csv,noheader)
nvidia-smi -i "$CUDA_VISIBLE_DEVICES"
flock -n -F "$SFM_ROOT/tmp/photo-tryon-20261106-gpu.lock" \
  "$py" -m backend.flux_worker --weights "$SFM_ROOT/models/photo-tryon-20261106/flux"
```

另一终端可用 `curl --noproxy '*' http://127.0.0.1:8787/internal/health` 查看。仅监听服务器回环，后端需在同一台主机访问或使用负责人控制的 SSH 转发。保持前台运行，联调完用 Ctrl+C 正常退出，再确认端口关闭和显存回到空闲。推理环境无 COS/Aidge 配置，模型以离线模式加载；原图和结果只在请求期间处理，验证输出另存在项目受控目录。

## 本次实测

| 项目 | 结果 |
| --- | --- |
| 启动加载 | 5.165 秒 |
| 回环 HTTP 生成 | 首次 4.085 秒、热请求 3.392 秒，输出 576 × 864 PNG，与同一组红色 T 恤离线基线逐像素一致 |
| 并发与超时 | 第二个请求 0.009 秒返回 `503 MODEL_BUSY`；700 毫秒预算约 0.705 秒返回 `504 INFERENCE_TIMEOUT`，当前 GPU 步骤收尾前仍返回忙碌 |
| 客户端断开与恢复 | 断开后不接受重叠任务；当前任务结束后用相同输入恢复出图，未返回迟到的超时结果 |
| GPU | PyTorch 峰值分配约 16,831 MiB；模型进程驻留时卡上共 18,356 MiB；停止后从 18,356 MiB 回到 15 MiB，端口关闭 |
| 现有后端联调 | 使用 `create_app(provider=LocalModelProvider(...))` 和 SSH 回环转发，走游客上传、生成、轮询、下载、结束清理。CG + 已有黑色 T 恤，4.250 秒，真实结果 576 × 864，幂等、防匿名下载、结束后撤销通过 |

4.250 秒是一次后端链路样例。镜子 App 拍照到结果可见的耗时仍待测。当前公共 CLI 接入与统计归 [#5](https://github.com/Miqi9880/smart-fitting-mirror/issues/5)，默认 MOCK。试验进程已关闭，发布代码和权重保留供联调启动。初次压测观察到 GPU 步骤跨越截止时间；当前实现在 HTTP 层按时返回超时，GPU 槽位保留到 CUDA 工作实际收尾。

## 后续微调

原权重、提示词和 5 组固定样例作为基线。微调使用独立环境与独立检查点，先核对 FLUX.2 klein 4B base 的训练方案和有许可的配对数据。人物原图、目标服装和正确穿着目标图用于训练，固定评估样例留作复核。对比人物保真、领口袖口、印花及纽扣、手部遮挡与推理耗时，再决定是否接入经过验证的适配器或新权重。数据与检查点放在 `/data_new/smart-fitting-mirror/` 下；训练资源另测。
