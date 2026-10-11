"""Standalone V01-V07 scene verification for the synthetic fitting-mirror pipeline.

This tool checks PNG anchors and a Python model of torso destination coordinates using
synthetic body poses. It does not execute the Android renderer, draw a warped garment,
or test frame ownership, display mirroring, lifecycle or device performance.

Its geometry conventions come from these implementations in the repository:

* backend/service.py publishes the garment anchors, and
  backend/tests/test_service.py asserts the four anchors land on opaque pixels.
* android/feature-tryon/.../TorsoWarp.kt maps those anchors onto the body with
  0.5 + value * (size - 1) and refuses to render for partial/lost/crossed torsos.

Scene intent matches docs/no-device-integration.md:

    V01 STILL     four torso points, tracking              garment drawn, anchors on cloth
    V02 TRANSLATE torso shifts right/left by 0.1            equal movement, unchanged span
    V03 SCALE     body scale 0.7x / 1.2x plus extra values  anchors scale proportionally
    V04 PARTIAL   left shoulder missing                    degrade, never reuse stale state
    V05 LOST      no keypoints at all                      hide the garment, no residue
    V06 RECOVERY  lost -> reappear with a new pose         re-lock, no reused stale state
    V07 VIEWPORT  portrait vs landscape image              coordinate round-trip only

Every verify_vNN returns a SceneResult; run_all prints a summary and exits non-zero when
any scene fails. Read-only: no asset or repository file is written.

Usage (repository root, Pillow installed):
    python tools/verify_scenes.py
    python tools/verify_scenes.py --assets backend/assets --verbose
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment guard
    raise SystemExit("需要 Pillow：pip install -r backend/requirements.txt")

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ASSETS = ROOT / "backend" / "assets"
GARMENT_IDS = ("upper_blue", "upper_preview", "upper_sand")

# Garment anchors, anatomically named (wearer's right/left), as published by
# backend/service.py GET /garments and documented in backend/assets/README.md.
LEFT_SHOULDER = (0.75, 0.15)
RIGHT_SHOULDER = (0.25, 0.15)
LEFT_HIP = (0.70, 0.85)
RIGHT_HIP = (0.30, 0.85)
TORSO_ORDER = ("right_shoulder", "left_shoulder", "right_hip", "left_hip")
# Same perimeter traversal as TorsoWarp.kt. Source and destination must use matching order.
WARP_ORDER = ("left_shoulder", "right_shoulder", "right_hip", "left_hip")
GARMENT_ANCHORS = {"right_shoulder": RIGHT_SHOULDER, "left_shoulder": LEFT_SHOULDER,
                   "right_hip": RIGHT_HIP, "left_hip": LEFT_HIP}

ANCHOR_ALPHA_MIN = 10    # alpha <= 10 counts as transparent background
PIXEL_TOLERANCE = 1e-4   # float slack when comparing pixel-centre coordinates

# Synthetic body poses, normalized to the canonical upright unmirrored frame.
STILL = {"right_shoulder": (0.35, 0.25), "left_shoulder": (0.65, 0.25),
         "right_hip": (0.40, 0.65), "left_hip": (0.60, 0.65)}


# --------------------------------------------------------------------------------------
# Results
# --------------------------------------------------------------------------------------
@dataclass
class SceneResult:
    scene: str
    title: str
    passed: bool
    reason: str
    details: list[str] = field(default_factory=list)

    def render(self, verbose: bool) -> str:
        head = "PASS" if self.passed else "FAIL"
        text = "[{0}] {1} {2} — {3}".format(head, self.scene, self.title, self.reason)
        if verbose and self.details:
            text += "\n" + "\n".join("        " + line for line in self.details)
        return text


# --------------------------------------------------------------------------------------
# Geometry helpers (Python model; not a replacement for Android tests)
# --------------------------------------------------------------------------------------
def to_pixels(points: dict[str, tuple[float, float]], width: int, height: int
              ) -> dict[str, tuple[float, float]]:
    """Normalized -> pixel centre: 0.5 + value * (size - 1)."""
    return {name: (0.5 + x * (width - 1), 0.5 + y * (height - 1))
            for name, (x, y) in points.items()}


def scaled_body(pose: dict[str, tuple[float, float]], factor: float,
                centre: tuple[float, float] = (0.5, 0.45)) -> dict[str, tuple[float, float]]:
    """Scale a pose about a centre, exactly like SyntheticScene.scale in Android."""
    cx, cy = centre
    return {name: (cx + (x - cx) * factor, cy + (y - cy) * factor)
            for name, (x, y) in pose.items()}


def tracking_state(pose: dict[str, tuple[float, float] | None]) -> str:
    """Torso-only fixture state; all present points are assumed reliable (confidence >= .5)."""
    if all(pose.get(name) is not None for name in TORSO_ORDER):
        return "tracking"
    return "partial" if any(pose.get(name) is not None for name in TORSO_ORDER) else "lost"


def torso_warp_source(frame_pose: dict[str, tuple[float, float] | None],
                      frame_size: tuple[int, int]) -> dict[str, tuple[float, float]] | None:
    """Destination anchors for the garment, or None when the torso cannot be used.

    Models only the destination-quad geometry of TorsoWarp.points(). Present fixture
    points are assumed reliable; confidence handling and source-image warping are not
    exercised here. Convexity and size thresholds operate in normalized coordinates.
    """
    frame_w, frame_h = frame_size
    if frame_w <= 0 or frame_h <= 0:
        return None
    if any(frame_pose.get(name) is None for name in TORSO_ORDER):
        return None
    ordered = [frame_pose[n] for n in WARP_ORDER]
    if any(not (0 <= x <= 1 and 0 <= y <= 1) for x, y in ordered):
        return None
    # Degenerate or self-crossed quads must never explode the perspective warp.
    winding = 0.0
    area = 0.0
    for index in range(4):
        ax, ay = ordered[index]
        bx, by = ordered[(index + 1) % 4]
        cx, cy = ordered[(index + 2) % 4]
        dx, dy = bx - ax, by - ay
        if dx * dx + dy * dy < PIXEL_TOLERANCE:
            return None
        cross = dx * (cy - by) - dy * (cx - bx)
        if abs(cross) < PIXEL_TOLERANCE or (winding != 0.0 and cross * winding <= 0.0):
            return None
        winding = cross
        area += ax * by - bx * ay
    if abs(area) <= 0.002:
        return None
    return to_pixels({n: frame_pose[n] for n in TORSO_ORDER}, frame_w, frame_h)


def anchor_alpha(image: Image.Image, anchor: tuple[float, float]) -> int:
    """Alpha at the pixel that a normalized garment anchor lands on."""
    width, height = image.size
    x = round(anchor[0] * (width - 1))
    y = round(anchor[1] * (height - 1))
    return image.getchannel("A").getpixel((x, y))


# --------------------------------------------------------------------------------------
# Asset loading (read-only)
# --------------------------------------------------------------------------------------
class Assets:
    def __init__(self, directory: Path):
        self.directory = directory
        self.images: dict[str, Image.Image] = {}
        self.problems: list[str] = []
        for garment in GARMENT_IDS:
            path = directory / (garment + ".png")
            if not path.exists():
                self.problems.append("asset missing: {0}".format(path))
                continue
            image = Image.open(path)
            self.images[garment] = image.convert("RGBA") if image.mode != "RGBA" else image

    @property
    def ready(self) -> bool:
        return not self.problems and len(self.images) == len(GARMENT_IDS)

    def size(self, garment: str) -> tuple[int, int]:
        return self.images[garment].size


def _guard(assets: Assets) -> str | None:
    if assets.ready:
        return None
    return "素材不可用：" + "；".join(assets.problems or ["未知原因"])


# --------------------------------------------------------------------------------------
# V01 - standing: the four garment anchors must sit on the shirt
# --------------------------------------------------------------------------------------
def verify_v01(assets: Assets, verbose: bool) -> SceneResult:
    title = "站立：锚点落在衣服上"
    problem = _guard(assets)
    if problem:
        return SceneResult("V01", title, False, problem)
    details: list[str] = []
    transparent: list[str] = []
    for garment in GARMENT_IDS:
        image = assets.images[garment]
        width, height = image.size
        if (width, height) != (600, 800):
            transparent.append("{0}: 尺寸 {1}x{2} 不是 600x800".format(garment, width, height))
        for name in TORSO_ORDER:
            anchor = GARMENT_ANCHORS[name]
            alpha = anchor_alpha(image, anchor)
            if alpha <= ANCHOR_ALPHA_MIN:
                transparent.append("{0}/{1} norm=({2:.2f},{3:.2f}) alpha={4} 落在透明背景".format(
                    garment, name, anchor[0], anchor[1], alpha))
            details.append("{0:<14} {1:<15} alpha={2:>3}".format(garment, name, alpha))
    if transparent:
        return SceneResult("V01", title, False, "有锚点落在透明背景上：" + "；".join(transparent), details)
    return SceneResult("V01", title, True, "三张图共 12 个锚点全部落在不透明衣服像素上", details)


# --------------------------------------------------------------------------------------
# V02 - translation: all torso points move together without changing size
# --------------------------------------------------------------------------------------
def verify_v02(assets: Assets, verbose: bool) -> SceneResult:
    title = "左右移动：四锚点整体平移，不额外缩放"
    problem = _guard(assets)
    if problem:
        return SceneResult("V02", title, False, problem)
    details: list[str] = []
    failures: list[str] = []
    for garment in GARMENT_IDS:
        width, height = assets.size(garment)
        base = torso_warp_source(STILL, (width, height))
        if base is None:
            failures.append("{0}: 基准躯干锚点不可用".format(garment))
            continue
        for shift in (0.0, 0.1, -0.1):
            pose = {n: (x + shift, y) for n, (x, y) in STILL.items()}
            moved = torso_warp_source(pose, (width, height))
            if moved is None:
                failures.append("{0}: shift={1} 时锚点不可用".format(garment, shift))
                continue
            for name in TORSO_ORDER:
                dx = moved[name][0] - base[name][0]
                dy = moved[name][1] - base[name][1]
                if max(abs(dx - shift * (width - 1)), abs(dy)) > PIXEL_TOLERANCE:
                    failures.append("{0}/{1}: 平移量错误或发生额外缩放".format(garment, name))
            details.append("{0:<14} shift={1:+.1f} 四点预期共同位移=({2:+.2f},0.00)px".format(
                garment, shift, shift * (width - 1)))
    if failures:
        return SceneResult("V02", title, False, "；".join(failures), details)
    return SceneResult("V02", title, True, "四点按 0 / +0.1 / -0.1 整体平移，点间距离不变", details)


# --------------------------------------------------------------------------------------
# V03 - size change: anchors must scale proportionally with the body
# --------------------------------------------------------------------------------------
def verify_v03(assets: Assets, verbose: bool) -> SceneResult:
    title = "大小变化：0.7x / 1.2x 及额外倍率等比缩放"
    problem = _guard(assets)
    if problem:
        return SceneResult("V03", title, False, problem)
    details: list[str] = []
    failures: list[str] = []
    for garment in GARMENT_IDS:
        width, height = assets.size(garment)
        baseline = torso_warp_source(STILL, (width, height))
        if baseline is None:
            failures.append("{0}: 基准躯干锚点不可用".format(garment))
            continue
        for factor in (0.5, 0.7, 1.0, 1.2, 2.0):
            scaled = torso_warp_source(scaled_body(STILL, factor), (width, height))
            if scaled is None:
                failures.append("{0}: scale={1} 时锚点退化".format(garment, factor))
                continue
            for name in TORSO_ORDER:
                expected = to_pixels({name: scaled_body(STILL, factor)[name]}, width, height)[name]
                got = scaled[name]
                error = max(abs(got[0] - expected[0]), abs(got[1] - expected[1]))
                if error > 0.5:
                    failures.append("{0} scale={1} {2} 未等比缩放 (误差 {3:.3f}px)".format(
                        garment, factor, name, error))
            span_base = max(p[0] for p in baseline.values()) - min(p[0] for p in baseline.values())
            span_got = max(p[0] for p in scaled.values()) - min(p[0] for p in scaled.values())
            ratio = span_got / span_base if span_base else 0.0
            if abs(ratio - factor) > 0.02:
                failures.append("{0} scale={1} 实际宽度比 {2:.4f}".format(garment, factor, ratio))
            details.append("{0:<14} scale={1:<4} 宽度比={2:.4f}".format(garment, factor, ratio))
    if failures:
        return SceneResult("V03", title, False, "；".join(failures), details)
    return SceneResult("V03", title, True, "锚点按 0.7x/1.2x 及额外 0.5x/1x/2x 等比缩放", details)


# --------------------------------------------------------------------------------------
# V04 - missing left shoulder: degrade, never reuse stale positions
# --------------------------------------------------------------------------------------
def verify_v04(assets: Assets, verbose: bool) -> SceneResult:
    title = "缺少左肩：降级处理，不沿用旧锚点"
    problem = _guard(assets)
    if problem:
        return SceneResult("V04", title, False, problem)
    details: list[str] = []
    failures: list[str] = []
    available = {n: STILL[n] for n in ("right_shoulder", "right_hip", "left_hip")}
    for garment in GARMENT_IDS:
        width, height = assets.size(garment)
        state = tracking_state(dict(available, left_shoulder=None))
        if state != "partial":
            failures.append("{0}: 缺左肩时状态应为 partial，实际 {1}".format(garment, state))
        warp = torso_warp_source(dict(available, left_shoulder=None), (width, height))
        if warp is not None:
            failures.append("{0}: 缺左肩时仍返回四个锚点，可能沿用旧位置".format(garment))
        # Degrading must reuse nothing: three of four points is not a torso.
        if len(available) + 1 == len(TORSO_ORDER) and warp is not None:
            failures.append("{0}: 三点不足却继续渲染".format(garment))
        details.append("{0:<14} state={1:<8} warp={2}".format(garment, state, "None(隐藏)" if warp is None else "渲染"))
    if failures:
        return SceneResult("V04", title, False, "；".join(failures), details)
    return SceneResult("V04", title, True, "缺左肩 -> partial，Python 几何模型返回 None", details)


# --------------------------------------------------------------------------------------
# V05 - lost: no keypoints at all must hide the garment
# --------------------------------------------------------------------------------------
def verify_v05(assets: Assets, verbose: bool) -> SceneResult:
    title = "丢失：不给锚点时必须隐藏衣服"
    problem = _guard(assets)
    if problem:
        return SceneResult("V05", title, False, problem)
    details: list[str] = []
    failures: list[str] = []
    empty: dict[str, tuple[float, float] | None] = {name: None for name in TORSO_ORDER}
    if tracking_state(empty) != "lost":
        failures.append("零锚点输入的状态应为 lost")
    for garment in GARMENT_IDS:
        width, height = assets.size(garment)
        warp = torso_warp_source(empty, (width, height))
        if warp is not None:
            failures.append("{0}: 零锚点仍返回渲染结果".format(garment))
        details.append("{0:<14} 锚点全空 -> warp={1} (期望隐藏)".format(
            garment, "None" if warp is None else "非 None"))
    if failures:
        return SceneResult("V05", title, False, "；".join(failures), details)
    return SceneResult("V05", title, True, "零锚点 -> lost，Python 几何模型返回 None", details)


# --------------------------------------------------------------------------------------
# V06 - recovery: after a loss the new pose must be re-locked, state not reused
# --------------------------------------------------------------------------------------
def verify_v06(assets: Assets, verbose: bool) -> SceneResult:
    title = "恢复：先丢失再恢复，不复用旧状态"
    problem = _guard(assets)
    if problem:
        return SceneResult("V06", title, False, problem)
    details: list[str] = []
    failures: list[str] = []
    lost_pose: dict[str, tuple[float, float] | None] = {name: None for name in TORSO_ORDER}
    # Reappear in a *different* pose so a reused stale quad would be visibly wrong.
    new_pose = {"right_shoulder": (0.30, 0.28), "left_shoulder": (0.70, 0.28),
                "right_hip": (0.36, 0.70), "left_hip": (0.64, 0.70)}
    for garment in GARMENT_IDS:
        width, height = assets.size(garment)
        before = torso_warp_source(STILL, (width, height))
        partial = torso_warp_source(dict(STILL, left_shoulder=None), (width, height))
        hidden = torso_warp_source(lost_pose, (width, height))
        after = torso_warp_source(new_pose, (width, height))
        if partial is not None or hidden is not None:
            failures.append("{0}: partial/lost 阶段未返回 None".format(garment))
        if before is None or after is None:
            failures.append("{0}: 恢复阶段锚点不可用".format(garment))
            continue
        # Recovered anchors must match the NEW pose, not the pre-loss pose.
        for name in TORSO_ORDER:
            expected = to_pixels({name: new_pose[name]}, width, height)[name]
            got = after[name]
            if max(abs(got[0] - expected[0]), abs(got[1] - expected[1])) > 0.5:
                failures.append("{0}: 恢复后 {1} 未重新定位".format(garment, name))
        stale_gap = min(max(abs(after[n][0] - before[n][0]), abs(after[n][1] - before[n][1]))
                        for n in TORSO_ORDER)
        if stale_gap <= PIXEL_TOLERANCE:
            failures.append("{0}: 恢复后锚点与丢失前完全相同，疑似复用旧状态".format(garment))
        details.append("{0:<14} 隐藏={1} 重新定位最小位移={2:.2f}px".format(
            garment, hidden is None, stale_gap))
    if failures:
        return SceneResult("V06", title, False, "；".join(failures), details)
    return SceneResult("V06", title, True, "Python 模型的 partial/lost 返回 None，恢复坐标匹配新姿态", details)


# --------------------------------------------------------------------------------------
# V07 - viewport: portrait vs landscape mapping must stay consistent
# --------------------------------------------------------------------------------------
def verify_v07(assets: Assets, verbose: bool) -> SceneResult:
    title = "横竖画幅：锚点映射保持一致"
    problem = _guard(assets)
    if problem:
        return SceneResult("V07", title, False, problem)
    details: list[str] = []
    failures: list[str] = []
    viewports = (("portrait", 480, 640), ("landscape", 640, 480))
    for garment in GARMENT_IDS:
        asset_w, asset_h = assets.size(garment)
        for label, width, height in viewports:
            if torso_warp_source(STILL, (asset_w, asset_h)) is None:
                failures.append("{0}: 素材锚点退化".format(garment))
                break
            pixels = to_pixels({n: STILL[n] for n in TORSO_ORDER}, width, height)
            normalized = {n: ((pixels[n][0] - 0.5) / (width - 1), (pixels[n][1] - 0.5) / (height - 1))
                          for n in TORSO_ORDER}
            for name in TORSO_ORDER:
                got, want = normalized[name], STILL[name]
                error = max(abs(got[0] - want[0]), abs(got[1] - want[1]))
                if error > 1e-9:
                    failures.append("{0}/{1}: {2} 归一化反算不等于输入 (误差 {3:.5f})".format(
                        garment, label, name, error))
            details.append("{0:<14} {1:<10} {2}x{3} 映射={4}".format(
                garment, label, width, height,
                " ".join("{0}:({1:.0f},{2:.0f})".format(n[:5], *pixels[n]) for n in TORSO_ORDER)))
            # Anchors must remain inside the frame in both orientations.
            for name in TORSO_ORDER:
                x, y = pixels[name]
                if not (0.5 <= x <= width - 0.5 and 0.5 <= y <= height - 0.5):
                    failures.append("{0}/{1}: {2} 越界".format(garment, label, name))
    if failures:
        return SceneResult("V07", title, False, "；".join(failures), details)
    return SceneResult("V07", title, True, "横竖画幅归一化映射一致，锚点均在画面内", details)


# --------------------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------------------
SCENES: tuple[tuple[str, Callable[[Assets, bool], SceneResult]], ...] = (
    ("V01", verify_v01), ("V02", verify_v02), ("V03", verify_v03), ("V04", verify_v04),
    ("V05", verify_v05), ("V06", verify_v06), ("V07", verify_v07),
)


def run_all(assets_dir: Path, verbose: bool = False, stream=None) -> int:
    out = stream or sys.stdout
    assets = Assets(assets_dir)
    print("智能穿衣镜 · V01–V07 合成场景验证", file=out)
    print("素材目录: {0}".format(assets_dir), file=out)
    if not assets.ready:
        for problem in assets.problems:
            print("  [缺失] " + problem, file=out)
    results: list[SceneResult] = []
    for scene, function in SCENES:
        print("-" * 78, file=out)
        try:
            result = function(assets, verbose)
        except Exception as error:  # keep the suite running and report honestly
            result = SceneResult(scene, "运行时异常", False, "{0}: {1}".format(type(error).__name__, error))
        results.append(result)
        print(result.render(verbose), file=out)
    passed = sum(1 for item in results if item.passed)
    print("=" * 78, file=out)
    print("总结: {0}/{1} 场景通过".format(passed, len(results)), file=out)
    for item in results:
        print("  {0} {1:<4} {2}".format("PASS" if item.passed else "FAIL", item.scene, item.title), file=out)
    failed = [item.scene for item in results if not item.passed]
    if failed:
        print("失败场景: " + ", ".join(failed), file=out)
    print("=" * 78, file=out)
    print("本工具仅检查 PNG 和 Python 几何模型；未执行 Android 渲染、帧生命周期或 UI 验收，也不代表真人效果或设备性能。", file=out)
    return 0 if not failed else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="检查 V01–V07 合成场景的渲染预期")
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS,
                        help="素材目录，默认 backend/assets")
    parser.add_argument("--verbose", action="store_true", help="打印每个锚点的逐点明细")
    arguments = parser.parse_args(argv)
    return run_all(arguments.assets, arguments.verbose)


if __name__ == "__main__":
    raise SystemExit(main())
