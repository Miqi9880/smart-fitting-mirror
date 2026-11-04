# 独立 GPU 基础环境

按负责人2026-11-03／04要求，本次只准备通用环境，模型选择、源码、权重和专用依赖另行决定。环境路径为 `/data_new/smart-fitting-mirror/envs/torch-cu128-py310`，与公共后端的 Python 3.12 环境分开。

通用验证基线采用服务器现有 Python 3.10、PyTorch 2.8.0 / torchvision 0.23.0 的官方PyPI Linux发行包；该Torch发行声明CUDA 12.8依赖。版本组合见[PyTorch官方安装表](https://pytorch.org/get-started/previous-versions/#v280)，包来源为[torch](https://pypi.org/project/torch/2.8.0/)及[torchvision](https://pypi.org/project/torchvision/0.23.0/)；Blackwell／CUDA 12.8支持背景见[官方说明](https://pytorch.org/blog/pytorch-2-7/)。这不是对后续模型最终依赖的承诺，模型选定后按其实际兼容条件另建环境或调整锁定。

## 创建与使用

以下命令在Linux服务器执行。安装和缓存全部位于指定项目根，无需更改系统驱动、系统Python或其他人的环境。

```bash
SFM_ROOT=/data_new/smart-fitting-mirror
# 首次创建；已有环境时跳过，不覆盖其他环境。
python3 -m venv "$SFM_ROOT/envs/torch-cu128-py310"

# 将本目录三份脚本复制到服务器 src/environment/，然后执行：
source "$SFM_ROOT/src/environment/activate.sh"
python -m pip install --upgrade 'pip>=24,<26' setuptools wheel
bash "$SFM_ROOT/src/environment/install_base.sh"
```

`activate.sh`将HF、Torch、pip、CUDA、Triton、编译缓存和临时文件指向项目根下的 `cache/` 与 `tmp/`。现有10个项目子目录已建立；目录不存在时先由负责人按存储说明准备，不改用home。激活本身不选择GPU、下载权重或启动服务。

安装器从作者的PyPI发行元数据解析并校验CPython3.10/Linux x86_64 wheel的版本、HTTPS来源和SHA256，同时核对Torch的CUDA 12.8依赖；其他依赖也从PyPI下载。服务器实测该源传输较快。安装只在上述独立环境中进行，使用项目锁避免两个安装同时修改它。成功后在服务器保存实际wheel引用、`requirements-base.lock.txt`、pip来源与哈希报告及包版本清单。

## 验证

先看当前GPU空闲量，再明确选择一张卡；空闲快照不表示独占预约。

```bash
source /data_new/smart-fitting-mirror/src/environment/activate.sh
nvidia-smi --query-gpu=index,name,memory.used,memory.free --format=csv
python -m pip check
SFM_GPU_UUID="$(nvidia-smi -i 1 --query-gpu=uuid --format=csv,noheader)"
CUDA_VISIBLE_DEVICES="$SFM_GPU_UUID" OMP_NUM_THREADS=4 MKL_NUM_THREADS=4 \
  python "$SFM_ROOT/src/environment/gpu_smoke.py" \
  --output "$SFM_ROOT/logs/gpu-environment-smoke.json"
```

检查FP32／BF16矩阵、cuDNN卷积、反向传播、SDPA和torchvision CUDA NMS，仅使用程序构造的小张量。检查后进程结束释放显存。报告记录实际环境与基础检查，不加载人物图片、模型或权重，不将结果称为模型推理／微调验收。
