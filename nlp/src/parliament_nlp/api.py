"""Private HTTP boundary. Deployment binds loopback; no browser CORS or public exposure."""

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .schemas import AnalysisRequest, AnalysisResponse
from .service import AnalysisService


def create_app(service: AnalysisService, configuration_sha256: str | None = None, readiness=None) -> FastAPI:
    app = FastAPI(title="Parliament local NLP", docs_url=None, redoc_url=None, openapi_url=None)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request: Request, _error: RequestValidationError):
        # FastAPI's default validation response includes the offending input.
        return JSONResponse(status_code=422, content={"error": "invalid_analysis_request"})

    @app.get("/health")
    def health():
        from . import __version__
        return {"status": "ok", "schemaVersion": 2, "service": "parliament-nlp", "implementationVersion": __version__,
                "configurationSha256": configuration_sha256, "methods": sorted(service.factories),
                "readiness": readiness() if readiness else {}}

    @app.post("/v1/analyze", response_model=AnalysisResponse)
    def analyze(request: AnalysisRequest):
        return service.analyze(request)

    return app
