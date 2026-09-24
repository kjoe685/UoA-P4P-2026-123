import argparse
from pathlib import Path

from .config import load_settings


def main():
    parser = argparse.ArgumentParser(description="Private CPU sentiment and policy-stance analysis")
    parser.add_argument("command", choices=["serve", "download"])
    parser.add_argument("--config", type=Path, default=Path("config/models.json"))
    parser.add_argument("--cache", type=Path, default=Path(".models"))
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--methods", default="cardiff-sentiment,deberta-stance")
    args = parser.parse_args()
    settings = load_settings(args.config)
    if args.command == "download":
        from huggingface_hub import snapshot_download

        specs = {"cardiff-sentiment": settings.cardiff, "deberta-stance": settings.deberta}
        methods = args.methods.split(",")
        if any(method not in specs for method in methods):
            parser.error("Download methods must be cardiff-sentiment or deberta-stance")
        for method in methods:
            spec = specs[method]
            snapshot_download(spec.modelId, revision=spec.revision, cache_dir=str(args.cache),
                              max_workers=1,
                              allow_patterns=["*.json", "*.txt", "*.model", "*.safetensors", "pytorch_model.bin"],
                              ignore_patterns=["training_args.bin"])
            print(f"Cached {method} at {spec.revision}")
        return

    import uvicorn
    from .api import create_app
    from .service import AnalysisService, default_factories

    uvicorn.run(create_app(AnalysisService(default_factories(settings, args.cache))),
                host="127.0.0.1", port=args.port, workers=1, access_log=False)
