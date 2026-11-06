# Source this file after creating the independent server environment.
# This does not select a model, download weights, or start a service.
export SFM_ROOT=/data_new/smart-fitting-mirror
export HF_HOME="$SFM_ROOT/cache/huggingface"
export HF_DATASETS_CACHE="$HF_HOME/datasets"
export TORCH_HOME="$SFM_ROOT/cache/torch"
export XDG_CACHE_HOME="$SFM_ROOT/cache/xdg"
export PIP_CACHE_DIR="$SFM_ROOT/cache/pip"
export TMPDIR="$SFM_ROOT/tmp"
export PYTHONPYCACHEPREFIX="$SFM_ROOT/cache/pycache"
export CUDA_CACHE_PATH="$SFM_ROOT/cache/cuda"
export TRITON_CACHE_DIR="$SFM_ROOT/cache/triton"
export TORCHINDUCTOR_CACHE_DIR="$SFM_ROOT/cache/torchinductor"
export PYTHONNOUSERSITE=1
umask 027
source "$SFM_ROOT/envs/torch-cu128-py310/bin/activate"
