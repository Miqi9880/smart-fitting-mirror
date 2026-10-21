import argparse
import uvicorn
from .service import create_app

parser = argparse.ArgumentParser(description="智能穿衣镜本地模拟服务（无真实 AI 调用）")
parser.add_argument("--host", default="127.0.0.1")
parser.add_argument("--port", type=int, default=8000)
parser.add_argument("--catalog-db", default="data/catalog.sqlite3", help="Local persistent base catalog; no user images stored here")
parser.add_argument("--mode", choices=["success", "failure", "delayed", "timeout", "rate-limit"], default="success")
parser.add_argument("--catalog-profile", choices=["studio", "regression"], default="studio", help="regression is for historical synthetic tests only")
args = parser.parse_args()
uvicorn.run(create_app(args.mode, catalog_path=args.catalog_db,catalog_profile=args.catalog_profile), host=args.host, port=args.port, workers=1, access_log=False,
            proxy_headers=False)
