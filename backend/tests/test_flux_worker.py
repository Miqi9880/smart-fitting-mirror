from concurrent.futures import ThreadPoolExecutor
import io
from threading import Event

from fastapi.testclient import TestClient
from PIL import Image

from backend.flux_worker import MAX_REQUEST_BYTES, create_app, decode_image, outfit_stages, output_size


def png(size=(180, 640), color=(60, 70, 80)):
    with Image.new("RGB", size, color) as image:
        output = io.BytesIO()
        image.save(output, "PNG")
    return output.getvalue()


def submit(client, *, budget=1_000, cloth_type="tops", person=None):
    return client.post(
        "/internal/tryon",
        data={"budget_ms": str(budget), "cloth_type": cloth_type},
        files={
            "person_image": ("person.png", png() if person is None else person, "image/png"),
            "garment_image": ("garment.png", png(), "image/png"),
        },
    )


def test_real_route_validates_input_and_returns_png():
    def infer(person, garment, _deadline):
        assert person.size == garment.size == (180, 640)
        return Image.new("RGB", output_size(person), "blue")

    with TestClient(create_app(infer)) as client:
        assert client.get("/internal/health").json()["is_mock"] is False
        assert submit(client, cloth_type="bottoms").json()["error"]["code"] == "UNSUPPORTED_CLOTH_TYPE"
        assert submit(client, person=b"invalid").json()["error"]["code"] == "INVALID_INPUT"
        assert submit(client, budget=0).status_code == 400
        assert client.post("/internal/tryon", content=b"", headers={
            "Content-Length": str(MAX_REQUEST_BYTES + 1)
        }).status_code == 400
        result = submit(client)
        assert result.status_code == 200
        assert result.headers["content-type"] == "image/png"
        with Image.open(io.BytesIO(result.content)) as image:
            assert image.size == (240, 864)


def test_timeout_discards_late_png_and_slot_rejects_overlapping_inference():
    started, finish = Event(), Event()

    def infer(person, _garment, _deadline):
        started.set()
        assert finish.wait(4)
        return person.copy()

    with TestClient(create_app(infer)) as client, ThreadPoolExecutor() as pool:
        inflight = pool.submit(submit, client, budget=600)
        try:
            assert started.wait(4)
            busy = submit(client)
            assert busy.status_code == 503
            assert busy.json()["error"]["code"] == "MODEL_BUSY"
            late = inflight.result(timeout=2)
            assert late.status_code == 504
            assert late.json()["error"]["code"] == "INFERENCE_TIMEOUT"
            assert submit(client).json()["error"]["code"] == "MODEL_BUSY"
        finally:
            finish.set()
        # The synthetic worker has to exit before the slot can be reused.
        for _ in range(100):
            if submit(client).status_code == 200:
                break
            Event().wait(0.02)
        else:
            raise AssertionError("Worker did not release its slot")


def test_very_narrow_input_is_bounded_before_padding():
    with decode_image(png((8192, 1))) as image:
        assert image.size == (4096, 256)
        assert output_size(image) == (864, 48)


def test_outfit_route_keeps_references_paired_and_shares_the_single_job_slot():
    started, finish = Event(), Event()

    def infer_outfit(person, references, _deadline):
        assert [(kind, image.size) for kind, image in references] == [
            ("earrings", (96, 32)), ("upper_body", (180, 640)), ("skirt", (180, 640)),
        ]
        assert [[kind for kind, _ in stage] for stage in outfit_stages(references)] == [
            ["skirt"], ["upper_body"], ["earrings"],
        ]
        started.set()
        assert finish.wait(4)
        return person.copy()

    files = [
        ("person_image", ("person.png", png(), "image/png")),
        ("garment_image", ("studs.png", png((96, 32)), "image/png")),
        ("garment_image", ("shirt.png", png(), "image/png")),
        ("garment_image", ("skirt.png", png(), "image/png")),
    ]
    with TestClient(create_app(lambda person, *_: person.copy(), infer_outfit)) as client, ThreadPoolExecutor() as pool:
        assert client.get("/internal/health").json()["outfit_supported"] is True
        path = "/internal/outfit"
        assert client.post(path, files=files, data={"budget_ms": "3000", "kinds": "earrings,dress,skirt"}).status_code == 400
        job = pool.submit(client.post, path, files=files, data={"budget_ms": "3000", "kinds": "earrings,upper_body,skirt"})
        try:
            assert started.wait(2)
            assert submit(client).json()["error"]["code"] == "MODEL_BUSY"
        finally:
            finish.set()
        assert job.result(timeout=2).status_code == 200
