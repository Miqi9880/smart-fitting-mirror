"""Audit the installed video pack and MVP recommendation coverage without network access."""

from collections import defaultdict
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from backend.service import create_app
from backend.video_contracts import VideoRecommendationList, VideoRecommendationRequest
from tools.install_video_assets import check_installed


def verify_media(client, video):
    if video.source.kind != "licensed":
        raise ValueError(f"Unlicensed recommendation: {video.video_id}")
    for url, prefix, mime in (
        (video.playback_url, "/api/v1/video-media/", "video/mp4"),
        (video.cover_url, "/api/v1/video-covers/", "image/jpeg"),
    ):
        parsed = urlsplit(url or "")
        if parsed.netloc != client.base_url.netloc.decode() or not parsed.path.startswith(prefix):
            raise ValueError(f"Missing local media or poster: {video.video_id}")
        response = client.head(parsed.path)
        response.raise_for_status()
        if response.headers.get("content-type") != mime or int(response.headers.get("content-length", 0)) <= 0:
            raise ValueError(f"Unexpected media response: {video.video_id}")
    response = client.get(urlsplit(video.playback_url).path, headers={"Range": "bytes=0-15"})
    if response.status_code != 206 or len(response.content) != 16:
        raise ValueError(f"Byte range did not return media: {video.video_id}")


def session_headers(client):
    response = client.post("/api/v1/sessions")
    response.raise_for_status()
    return {"Authorization": "Bearer " + response.json()["session_token"]}


def audit(client):
    garments = defaultdict(list)
    headers = session_headers(client)
    response = client.get("/api/v1/garments", headers=headers)
    response.raise_for_status()
    for garment in response.json()["items"]:
        garments[garment["category"]].append(garment["garment_id"])
    client.delete("/api/v1/sessions/current", headers=headers).raise_for_status()
    if not garments:
        raise ValueError("The studio wardrobe is empty")

    covered = True
    verified = set()
    for category, ids in sorted(garments.items()):
        counts, category_counts = [], []
        for start in range(0, len(ids), 30):
            headers = session_headers(client)
            for garment_id in ids[start:start + 30]:
                request = VideoRecommendationRequest(garment_ids=[garment_id])
                response = client.post("/api/v1/video-recommendations", headers=headers, json=request.model_dump(mode="json"))
                response.raise_for_status()
                result = VideoRecommendationList.model_validate(response.json())
                result.validate_context(request)
                counts.append(result.available_count)
                category_counts.append(sum(any(match.dimension == "category" for match in item.matches)
                                           for item in result.items))
                for item in result.items:
                    video = item.video
                    if video.video_id not in verified:
                        verify_media(client, video)
                        verified.add(video.video_id)
            client.delete("/api/v1/sessions/current", headers=headers).raise_for_status()
        passing = sum(count >= 5 for count in counts)
        covered = covered and passing == len(ids)
        print(f"{category}: {passing}/{len(ids)} reach five, "
              f"{min(counts)}-{max(counts)} available, "
              f"{min(category_counts)}-{max(category_counts)} category matches in the displayed list")
    return covered, len(verified)


if __name__ == "__main__":
    installed = check_installed()
    with TemporaryDirectory(prefix="mirror-video-check-") as temp:
        app = create_app(catalog_path=Path(temp) / "wardrobe.sqlite3")
        with TestClient(app) as client:
            covered, verified = audit(client)
    print(f"Licensed pack: {installed} installed, {verified} reached and served in wardrobe recommendations.")
    if not covered:
        print("MVP shortfall: at least one wardrobe context has fewer than five related videos.")
    raise SystemExit(0 if covered else 1)
