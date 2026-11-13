"""Small GPU environment checks. No model, weights, photos, or HTTP service."""
import argparse
import json
import os
import platform
from pathlib import Path


def check():
    import torch
    import torchvision

    torch.set_num_threads(4)
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable; a CPU-only install is insufficient")
    if torch.cuda.device_count() != 1:
        raise RuntimeError("Expose exactly one chosen GPU with CUDA_VISIBLE_DEVICES")
    device = torch.device("cuda:0")
    free, _ = torch.cuda.mem_get_info(device)
    if free < 512 * 1024**2:
        raise RuntimeError("Less than 512 MiB free; do not run this check now")
    torch.cuda.reset_peak_memory_stats(device)
    torch.manual_seed(20261103)

    a = torch.randn(256, 256, device=device)
    b = torch.randn(256, 256, device=device)
    product = a @ b
    torch.testing.assert_close(product.cpu(), a.cpu() @ b.cpu(), rtol=1e-3, atol=1e-3)

    if not torch.cuda.is_bf16_supported():
        raise RuntimeError("The chosen GPU/runtime does not support bfloat16")
    ones = torch.ones(128, 128, device=device, dtype=torch.bfloat16)
    torch.testing.assert_close(ones @ ones, torch.full_like(ones, 128))

    # Exercise cuDNN, backward execution, SDPA and the torchvision CUDA extension.
    image = torch.ones(1, 3, 32, 32, device=device)
    kernel = torch.ones(8, 3, 3, 3, device=device, requires_grad=True)
    convolution = torch.nn.functional.conv2d(image, kernel)
    torch.testing.assert_close(convolution, torch.full_like(convolution, 27))
    convolution.mean().backward()
    assert kernel.grad is not None and torch.isfinite(kernel.grad).all().item()

    query = torch.ones(1, 2, 32, 16, device=device, dtype=torch.bfloat16)
    attention = torch.nn.functional.scaled_dot_product_attention(query, query, query)
    torch.testing.assert_close(attention, query)

    boxes = torch.tensor([[0, 0, 10, 10], [1, 1, 9, 9]], device=device, dtype=torch.float32)
    scores = torch.tensor([0.9, 0.8], device=device)
    kept = torchvision.ops.nms(boxes, scores, 0.5)
    assert kept.cpu().tolist() == [0]
    torch.cuda.synchronize(device)

    return {
        "result": "passed",
        "python": platform.python_version(),
        "torch": torch.__version__,
        "torchvision": torchvision.__version__,
        "cuda_runtime": torch.version.cuda,
        "cudnn": torch.backends.cudnn.version(),
        "visible_device_setting": os.environ.get("CUDA_VISIBLE_DEVICES"),
        "gpu": torch.cuda.get_device_name(device),
        "compute_capability": list(torch.cuda.get_device_capability(device)),
        "compiled_architectures": torch.cuda.get_arch_list(),
        "checks": ["fp32_matmul", "bf16_matmul", "cudnn_conv", "backward", "sdpa", "torchvision_cuda_nms"],
        "peak_allocated_mib": round(torch.cuda.max_memory_allocated(device) / 1024**2, 2),
        "model_loaded": False,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Optional local JSON evidence path")
    args = parser.parse_args()
    report = check()
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
