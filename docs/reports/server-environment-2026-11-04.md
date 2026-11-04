# 服务器通用 GPU 环境

2026-11-04，按负责人“先部署环境，具体模型与权重稍后决定”的要求，只准备独立基础环境。对应 #3，未选择试衣模型、克隆模型源码、下载权重、上传图片、运行推理或训练，也未启动模型HTTP服务。

## 位置与隔离

- 项目根：`/data_new/smart-fitting-mirror`。
- 虚拟环境：`envs/torch-cu128-py310`，使用服务器已有Python 3.10.12，不修改系统Python或公共后端Python 3.12。
- 激活、安装与检查脚本：`src/environment/`，仓库来源为[tools/model_env](../../tools/model_env/README.md)。
- HF、Torch、pip、CUDA、Triton、TorchInductor、Python字节码缓存位于`cache/`，临时目录为`tmp/`。不将环境／大文件缓存回落home。
- 只在独立虚拟环境中安装软件；未升级系统驱动、CUDA Toolkit或系统软件。两张卡的原有任务不迁移、不终止。

PyTorch 2.8.0 / torchvision 0.23.0采用作者在PyPI发布的Linux x86_64 CPython3.10发行包，固定版本并核对官方SHA256；Torch发行声明CUDA运行库12.8.90依赖。该服务器实测PyPI文件传输较快。实际包、来源与哈希、安装锁定分别保存于服务器`logs/env-base-pip-report.json`、`logs/env-base-packages.json`和`src/environment/requirements-base.lock.txt`。

## 验证

安装脚本退出码0，`pip check`报告无损坏依赖；shell语法检查通过。独立环境前缀和10个缓存／临时路径均确认落项目根下。

| 项目 | 实测 |
| --- | --- |
| Python | 3.10.12 |
| PyTorch运行版本 | 2.8.0+cu128 |
| torchvision运行版本 | 0.23.0+cu128 |
| CUDA运行库 | 12.8 |
| cuDNN版本编号 | 91002 |
| GPU | RTX PRO 5000 Blackwell，物理1号卡，以UUID显式选择 |
| Compute capability | 12.0，编译架构包含sm_120 |

`gpu_smoke.py`退出码0，六项均通过：FP32矩阵与CPU结果比较、BF16矩阵、cuDNN卷积、反向传播、SDPA和torchvision CUDA NMS。只使用程序构造的小张量；PyTorch张量分配峰值9.12MiB，不代表CUDA上下文总显存，也不代表未来模型的显存需求。

原始结果保存在服务器`logs/gpu-environment-smoke-20261104.json`与同名`.log`。模型标记为`model_loaded=false`；本次没有人物输入或生成结果。软件检查不代替后续模型效果、推理耗时或训练验证。

检查进程结束后，1号卡恢复到15MiB已用、48387MiB空闲；0号卡原有27977MiB占用保持。`models/`、`datasets/`、`eval/`、`outputs/`、`checkpoints/`文件数均为0。环境约6.4GiB、软件缓存约4.6GiB、项目临时目录约1.3GiB；三份服务器脚本与仓库源码SHA256一致。本轮未改变原有存储挂载。

## 使用与下一步

```bash
source /data_new/smart-fitting-mirror/src/environment/activate.sh
python -m pip check
# 先确认空闲卡，再运行小张量检查。
SFM_GPU_UUID="$(nvidia-smi -i 1 --query-gpu=uuid --format=csv,noheader)"
CUDA_VISIBLE_DEVICES="$SFM_GPU_UUID" OMP_NUM_THREADS=4 MKL_NUM_THREADS=4 \
  python "$SFM_ROOT/src/environment/gpu_smoke.py" \
  --output "$SFM_ROOT/logs/gpu-environment-smoke.json"
```

这是可替换的通用验证基线。负责人确定模型后，再核对代码／权重／样例许可、Python及CUDA兼容、专用依赖和预处理，并决定沿用或另建模型环境，然后下载固定版本权重、跑获准输入。环境可用不能记为模型已出图、微调成功或企业5—15秒指标达成；公共MOCK和Aidge/COS暂停状态保持。
