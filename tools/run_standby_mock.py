"""Local-only MOCK fixture for delayed cleanup acknowledgement; never enables a cloud provider."""
import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import uvicorn
from starlette.responses import JSONResponse
from backend.service import create_app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8014)
    parser.add_argument("--catalog-db", required=True, help="Separate test SQLite catalog; no user images stored")
    parser.add_argument("--cleanup-delay", type=float, default=10)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 < args.cleanup_delay <= 60:
        parser.error("Use a valid port and a cleanup delay in (0, 60] seconds")
    app = create_app("success", catalog_path=args.catalog_db)

    @app.middleware("http")
    async def slow_cleanup(request, call_next):
        if request.method == "DELETE" and request.url.path == "/api/v1/sessions/current":
            await asyncio.sleep(args.cleanup_delay)
            return JSONResponse({"error": {"code": "MOCK_CLEANUP_DELAY", "message": "delayed local cleanup",
                "retryable": True, "request_id": "generated-test"}}, status_code=503)
        return await call_next(request)

    uvicorn.run(app, host="127.0.0.1", port=args.port, access_log=False, proxy_headers=False)


if __name__ == "__main__":
    main()
