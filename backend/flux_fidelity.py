"""Keep identity pixels and suppress source-garment details in FLUX references."""

from PIL import Image, ImageChops, ImageFilter


_BOTTOM_LABELS = {"pants", "skirt"}
_GARMENT_LABELS = {
    "upper_body": {"top"},
    "lower_body": _BOTTOM_LABELS,
    "shorts": _BOTTOM_LABELS,
    "skirt": _BOTTOM_LABELS,
    "dress": {"top", "dress", *_BOTTOM_LABELS},
}
_PROTECTED_LABELS = {"face", "glasses"}


class PersonParser:
    def __init__(self, weights, device):
        import torch
        from transformers import AutoImageProcessor, AutoModelForSemanticSegmentation

        self.torch = torch
        self.device = device
        self.processor = AutoImageProcessor.from_pretrained(weights, local_files_only=True, use_fast=False)
        self.model = AutoModelForSemanticSegmentation.from_pretrained(weights, local_files_only=True).to(device).eval()
        self.labels = self.model.config.id2label
        expected = _PROTECTED_LABELS | set().union(*_GARMENT_LABELS.values())
        if not expected.issubset(self.labels.values()):
            raise ValueError("Unexpected labels in the person parsing checkpoint")

    def __call__(self, person: Image.Image) -> Image.Image:
        inputs = self.processor(images=person, return_tensors="pt").to(self.device)
        with self.torch.inference_mode():
            outputs = self.model(**inputs)
            segmentation, = self.processor.post_process_semantic_segmentation(
                outputs, target_sizes=[(person.height, person.width)]
            )
        return Image.fromarray(segmentation.to("cpu", dtype=self.torch.uint8).numpy())


def _mask(segmentation: Image.Image, labels: dict[int, str], names: set[str]) -> Image.Image:
    return segmentation.point([255 if labels.get(index) in names else 0 for index in range(256)])


def protected_face(segmentation: Image.Image, labels: dict[int, str]) -> Image.Image:
    with _mask(segmentation, labels, _PROTECTED_LABELS) as core:
        with core.filter(ImageFilter.GaussianBlur(2)) as edge:
            return ImageChops.lighter(core, edge)


def neutralize_clothes(person: Image.Image, segmentation: Image.Image, labels: dict[int, str], kinds) -> Image.Image:
    names = set().union(*(_GARMENT_LABELS.get(kind, ()) for kind in kinds))
    with _mask(segmentation, labels, names) as core:
        with core.filter(ImageFilter.GaussianBlur(2)) as edge:
            with Image.new("RGB", person.size, (140, 140, 140)) as neutral:
                return Image.composite(neutral, person, edge)
