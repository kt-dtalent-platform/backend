"""공통 에러 응답 (API 정리본 §1, D-13 제안안).

{ "error": { "code": "...", "message": "...", "fieldErrors": [...], "traceId": "..." } }
"""
import uuid

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class AppError(Exception):
    def __init__(self, status_code: int, code: str, message: str, field_errors: list[dict] | None = None,
                 extra: dict | None = None):
        self.status_code = status_code
        self.code = code
        self.message = message
        self.field_errors = field_errors
        self.extra = extra or {}


def _error_body(code: str, message: str, field_errors: list[dict] | None = None, extra: dict | None = None) -> dict:
    error = {"code": code, "message": message, "traceId": uuid.uuid4().hex[:12]}
    if field_errors:
        error["fieldErrors"] = field_errors
    if extra:
        error.update(extra)
    return {"error": error}


def register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def _app_error(_: Request, exc: AppError):
        return JSONResponse(status_code=exc.status_code, content=_error_body(exc.code, exc.message, exc.field_errors, exc.extra))

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError):
        field_errors = [
            {"field": ".".join(str(p) for p in err["loc"] if p != "body"), "reason": err["type"], "message": err["msg"]}
            for err in exc.errors()
        ]
        return JSONResponse(
            status_code=422,
            content=_error_body("VALIDATION_FAILED", "입력값을 확인해 주세요.", field_errors),
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_: Request, exc: StarletteHTTPException):
        code = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}.get(exc.status_code, "HTTP_ERROR")
        return JSONResponse(status_code=exc.status_code, content=_error_body(code, str(exc.detail)))
