"""Bounded single-worker execution with deadlines and disconnect handling."""

import asyncio
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from .errors import ServiceError


class WorkQueue:
    """Slots remain held until actual work finishes, even after an HTTP timeout."""

    def __init__(self, waiting):
        self.slots = threading.BoundedSemaphore(1 + waiting)
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="editor-worker")

    async def run(self, fn, *args, seconds=15, request=None):
        if not self.slots.acquire(blocking=False):
            raise ServiceError("BUSY", "処理中です。完了後に再試行してください", 429, True)
        deadline = time.monotonic() + seconds

        def task():
            try:
                if time.monotonic() >= deadline:
                    raise ServiceError("TIMEOUT", "実行待ちの期限を超えました", 504, True)
                result = fn(*args)
                if time.monotonic() >= deadline:
                    raise ServiceError("TIMEOUT", "処理の期限を超えました", 504, True)
                return result
            finally:
                self.slots.release()

        try:
            future = self.executor.submit(task)
        except Exception:
            self.slots.release()
            raise
        wrapped = asyncio.wrap_future(future)
        # Consume exceptions on detached work; never cancel a running native model call.
        wrapped.add_done_callback(lambda f: f.exception() if not f.cancelled() else None)
        while not wrapped.done():
            if time.monotonic() >= deadline:
                raise ServiceError("TIMEOUT", "処理の期限を超えました", 504, True)
            if request is not None and await request.is_disconnected():
                # A cancelled queued job never reaches the task's finally block.
                if future.cancel():
                    self.slots.release()
                raise ServiceError("CANCELLED", "処理を取り消しました", 499)
            await asyncio.wait([wrapped], timeout=min(0.1, max(0, deadline - time.monotonic())))
        return wrapped.result()

    def close(self):
        self.executor.shutdown(wait=False, cancel_futures=False)
