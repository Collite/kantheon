"""Timeout wrapper for model fitting via thread-based deadline.

Architecture note: the architecture doc calls for per-fit process-pool isolation
so a hung optimizer cannot block the server.  A true ProcessPoolExecutor approach
is viable for simple functions but serialising complex statsmodels/Prophet result
objects across process boundaries (via pickle) is fragile in practice.

Phase 2 uses a ThreadPoolExecutor with a hard deadline instead:
  - Statsmodels and Prophet release the GIL for the numerical heavy lifting, so
    threads give meaningful concurrency on multi-core hosts.
  - A future Phase 3 upgrade can add true process isolation for fit *invocation*
    while keeping model objects in the main process.
"""
from __future__ import annotations

import concurrent.futures
import logging
from typing import Callable, TypeVar

logger = logging.getLogger(__name__)

T = TypeVar("T")

_THREAD_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=4,
    thread_name_prefix="metis-fit",
)


def run_with_timeout(fn: Callable[[], T], timeout_ms: int, label: str) -> T:
    """Submit *fn* to the shared thread pool; raise *TimeoutError* if not done in *timeout_ms*."""
    future = _THREAD_POOL.submit(fn)
    try:
        return future.result(timeout=timeout_ms / 1000)
    except concurrent.futures.TimeoutError:
        raise TimeoutError(f"{label} timed out after {timeout_ms}ms")
