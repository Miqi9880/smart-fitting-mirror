import argparse
from pathlib import Path
import uvicorn
from .service import create_app
from .usage_metrics import UsageMetrics

parser = argparse.ArgumentParser(description="智能穿衣镜后端服务")
parser.add_argument("--host", default="127.0.0.1")
parser.add_argument("--port", type=int, default=8000)
parser.add_argument("--catalog-db", default="data/catalog.sqlite3", help="Local persistent base catalog; no user images stored here")
parser.add_argument("--usage-metrics-db", default="data/usage-metrics.sqlite3", help="Local daily anonymous aggregate; no IDs or images stored")
parser.add_argument("--mode", choices=["success", "failure", "delayed", "timeout", "rate-limit"], default="success")
parser.add_argument("--provider", choices=["mock", "aidge-cos", "local-model"], default="mock")
parser.add_argument("--local-model-url", default=None,
                    help="显式配置的本机回环模型端点，例如 http://127.0.0.1:8787")
parser.add_argument("--enable-real-provider", action="store_true", help="明确启用Aidge/COS真实调用；会产生云端服务与流量费用，默认关闭")
parser.add_argument("--cos-env-file", type=Path, default=Path(".env.cos"))
parser.add_argument("--aliyun-env-file", type=Path, default=Path(".env.aliyun"))
parser.add_argument("--catalog-profile", choices=["studio", "regression"], default="studio", help="regression is for historical synthetic tests only")
args = parser.parse_args()
provider = None
provider_disabled = args.provider == "aidge-cos" and not args.enable_real_provider
if args.enable_real_provider and args.provider != "aidge-cos":
    parser.error("--enable-real-provider 只能与 --provider aidge-cos 一起使用")
if args.provider == "local-model":
    if args.local_model_url is None:
        parser.error("--provider local-model 必须同时配置 --local-model-url")
    if args.mode != "success":
        parser.error("本地模型提供方不能与模拟故障模式组合")
    from .local_model_provider import LocalModelProvider
    try:
        provider = LocalModelProvider(args.local_model_url)
    except ValueError as error:
        parser.error(str(error))
elif args.local_model_url is not None:
    parser.error("--local-model-url 只能与 --provider local-model 一起使用")
if args.provider == "aidge-cos" and args.enable_real_provider:
    from .tryon_provider import AidgeCosProvider
    from .volc_tryon_smoke import ConfigurationError
    try:
        provider = AidgeCosProvider.from_env(args.cos_env_file, args.aliyun_env_file)
    except ConfigurationError as error:
        parser.error(str(error))
uvicorn.run(create_app(args.mode, catalog_path=args.catalog_db,catalog_profile=args.catalog_profile,provider=provider,
                       provider_disabled=provider_disabled,
                       usage_metrics=UsageMetrics(args.usage_metrics_db)), host=args.host, port=args.port, workers=1, access_log=False,
            proxy_headers=False)
