"""Loopback-only MOCK fixtures for video UI integration. Never enables a cloud provider."""
import argparse
import asyncio
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import uvicorn
from starlette.responses import JSONResponse
from backend.service import create_app, detail
from backend.video_library import VideoLibrary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8018)
    parser.add_argument('--catalog-db', required=True)
    parser.add_argument('--fault', choices=['none','empty','fail-once','expire-once','delay','media-missing'], default='none')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('Use a valid port')
    app = create_app(catalog_path=args.catalog_db, video_library=VideoLibrary() if args.fault == 'empty' else None)
    used = False
    missing_media = None

    @app.middleware('http')
    async def video_fault(request, call_next):
        nonlocal used, missing_media
        if args.fault == 'media-missing' and request.url.path.startswith('/api/v1/video-media/'):
            # MediaPlayer may internally retry HTTP GET; keep one ID unavailable,
            # so the fixture cannot accidentally recover before the UI sees it.
            if missing_media is None:
                missing_media = request.url.path
            if request.url.path == missing_media:
                return JSONResponse({'error':detail('RESOURCE_NOT_FOUND', '本地媒体测试缺失', False)}, status_code=404)
        if request.method == 'POST' and request.url.path == '/api/v1/video-recommendations':
            if args.fault == 'delay':
                await asyncio.sleep(10)
            if not used and args.fault in {'fail-once','expire-once'}:
                used = True
                expired = args.fault == 'expire-once'
                return JSONResponse({'error':detail('SESSION_INVALID' if expired else 'SERVICE_UNAVAILABLE',
                    '本地视频测试故障', not expired)}, status_code=401 if expired else 503,
                    headers={'Cache-Control':'no-store'})
        return await call_next(request)

    uvicorn.run(app, host='127.0.0.1', port=args.port, access_log=False, proxy_headers=False)


if __name__ == '__main__':
    main()
