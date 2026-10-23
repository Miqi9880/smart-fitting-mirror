import argparse
from pathlib import Path
import uvicorn
from .service import create_app

parser = argparse.ArgumentParser(description="智能穿衣镜后端服务")
parser.add_argument("--host", default="127.0.0.1")
parser.add_argument("--port", type=int, default=8000)
parser.add_argument("--catalog-db", default="data/catalog.sqlite3", help="Local persistent base catalog; no user images stored here")
parser.add_argument("--mode", choices=["success", "failure", "delayed", "timeout", "rate-limit"], default="success")
parser.add_argument("--provider", choices=["mock", "aidge-cos"], default="mock")
parser.add_argument("--cos-env-file", type=Path, default=Path(".env.cos"))
parser.add_argument("--aliyun-env-file", type=Path, default=Path(".env.aliyun"))
parser.add_argument("--catalog-profile", choices=["studio", "regression"], default="studio", help="regression is for historical synthetic tests only")
args = parser.parse_args()
provider = None
if args.provider == "aidge-cos":
    from .tryon_provider import AidgeCosProvider
    from .volc_tryon_smoke import ConfigurationError
    try:
        provider = AidgeCosProvider.from_env(args.cos_env_file, args.aliyun_env_file)
    except ConfigurationError as error:
        parser.error(str(error))
uvicorn.run(create_app(args.mode, catalog_path=args.catalog_db,catalog_profile=args.catalog_profile,provider=provider), host=args.host, port=args.port, workers=1, access_log=False,
            proxy_headers=False)
