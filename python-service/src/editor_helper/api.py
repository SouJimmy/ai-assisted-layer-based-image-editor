import asyncio
import hmac
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse, Response

from .errors import ServiceError
from .image_cache import ImageCache
from .model_runtime import MobileSamBackend
from .psd_importer import import_psd
from .work_queue import WorkQueue


class Point(BaseModel):
    model_config = ConfigDict(extra="forbid")
    x: StrictInt
    y: StrictInt
    label: StrictInt = Field(ge=0, le=1)


class Segment(BaseModel):
    model_config = ConfigDict(extra="forbid")
    imageId: StrictStr = Field(min_length=1, max_length=128)
    sourceRevision: StrictStr = Field(min_length=1, max_length=128)
    requestId: StrictStr = Field(min_length=1, max_length=128)
    points: list[Point] = Field(min_length=1, max_length=64)


def create_app(token, manifest=None, backend=None):
    if not token:
        raise ValueError("EDITOR_HELPER_TOKEN is required")
    backend = backend or MobileSamBackend(manifest)
    cache = ImageCache(backend)
    model_queue, psd_queue = WorkQueue(2), WorkQueue(1)
    instance = str(uuid.uuid4())
    init_error = {"code": None}

    def initialize():
        try:
            backend.initialize()
        except ServiceError as e:
            init_error["code"] = e.code
        except Exception:
            init_error["code"] = "MODEL_INIT_FAILED"

    @asynccontextmanager
    async def lifespan(app):
        # Model initialization is serialized with all other model operations.
        init_task = asyncio.create_task(model_queue.run(initialize, seconds=60))
        init_task.add_done_callback(lambda f: f.exception() if not f.cancelled() else None)
        yield
        model_queue.close()
        psd_queue.close()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.instance = instance

    def error(code, message, status, request_id, retryable=False):
        return JSONResponse(
            {
                "error": {
                    "code": code,
                    "message": message,
                    "requestId": request_id,
                    "retryable": retryable,
                }
            },
            status_code=status,
            headers={"X-Api-Version": "1", "X-Request-Id": request_id},
        )

    @app.middleware("http")
    async def gate(request, call_next):
        request_id = request.headers.get("x-request-id", str(uuid.uuid4()))
        try:
            uuid.UUID(request_id)
        except ValueError:
            return error("INVALID_ARGUMENT", "要求IDが不正です", 400, str(uuid.uuid4()))
        request.state.request_id = request_id
        if not hmac.compare_digest(
            request.headers.get("authorization", "").encode("utf-8"),
            f"Bearer {token}".encode("utf-8"),
        ):
            return error("AUTH_FAILED", "認証に失敗しました", 401, request_id)
        if request.headers.get("x-api-version") != "1":
            return error("API_VERSION_UNSUPPORTED", "APIの版が一致しません", 426, request_id)
        limit = (
            (128 * 1024 * 1024 + 65536)
            if request.url.path == "/psd/import"
            else (64 * 1024 * 1024 + 65536)
        )
        if request.url.path == "/segment":
            limit = 65536
        try:
            # Bound incoming data before multipart parsing, even without Content-Length.
            chunks = []
            size = 0
            async for chunk in request.stream():
                size += len(chunk)
                if size > limit:
                    return error(
                        "RESOURCE_LIMIT", "送信データが上限を超えています", 413, request_id
                    )
                chunks.append(chunk)
            request._body = b"".join(chunks)
            response = await call_next(request)
        except ServiceError as e:
            return error(e.code, str(e), e.status, request_id, e.retryable)
        except Exception:
            return error("INTERNAL_ERROR", "処理に失敗しました", 500, request_id)
        response.headers["X-Api-Version"] = "1"
        response.headers["X-Request-Id"] = request_id
        return response

    @app.exception_handler(ServiceError)
    async def service_error(request, exc):
        return error(exc.code, str(exc), exc.status, request.state.request_id, exc.retryable)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return error("INVALID_ARGUMENT", "要求の形式が不正です", 422, request.state.request_id)

    @app.exception_handler(HTTPException)
    async def http_error(request, exc):
        return error(
            "INVALID_ARGUMENT",
            "要求先または要求形式が不正です",
            exc.status_code,
            request.state.request_id,
        )

    @app.get("/health")
    async def health():
        ready = backend.ready
        return {
            "state": "READY" if ready else "DEGRADED" if init_error["code"] else "STARTING",
            "aiReady": ready,
            "psdReady": True,
            "device": backend.device,
            "modelVersion": backend.model_version,
            "serviceInstanceId": instance,
            "aiError": init_error["code"],
        }

    async def upload(request, field, limit):
        try:
            async with request.form(max_files=1, max_fields=2, max_part_size=limit) as form:
                file = form.get(field)
                if file is None or not hasattr(file, "read"):
                    raise ValueError("missing upload")
                data = await file.read(limit + 1)
                if len(data) > limit:
                    raise ServiceError("RESOURCE_LIMIT", "ファイルが上限を超えています", 413)
                revision = form.get("sourceRevision")
                return data, revision
        except ServiceError:
            raise
        except Exception as exc:
            raise ServiceError("INVALID_ARGUMENT", "multipartの形式が不正です") from exc

    @app.post("/image", status_code=201)
    async def register(request: Request):
        if not backend.ready:
            raise ServiceError(
                init_error["code"] or "MODEL_NOT_READY", "AIの準備ができていません", 503, True
            )
        data, revision = await upload(request, "image", 64 * 1024 * 1024)
        if not isinstance(revision, str) or not 1 <= len(revision) <= 128:
            raise ServiceError("INVALID_ARGUMENT", "画像世代が不正です")
        result = await model_queue.run(cache.register, data, revision, seconds=25, request=request)
        result["serviceInstanceId"] = instance
        return result

    @app.post("/segment")
    async def segment(body: Segment, request: Request):
        if body.requestId != request.state.request_id:
            raise ServiceError("INVALID_ARGUMENT", "要求IDが一致しません")
        return await model_queue.run(cache.segment, body.model_dump(), seconds=12, request=request)

    @app.delete("/image/{image_id}", status_code=204)
    async def release(image_id: str, request: Request):
        cache.mark_deleted(image_id)
        await model_queue.run(cache.release, image_id, seconds=12, request=request)
        return Response(status_code=204)

    @app.post("/psd/import")
    async def psd(request: Request):
        data, _ = await upload(request, "file", 128 * 1024 * 1024)
        result = await psd_queue.run(import_psd, data, seconds=55, request=request)
        return Response(result, media_type="application/zip")

    return app
