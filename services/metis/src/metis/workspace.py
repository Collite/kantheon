"""
Session workspace for Metis — stores DataFrames (Arrow IPC bytes) and fitted models.

Keys: (session_id, name). One unified store with kind discriminator.
Thread-safe via threading.Lock (grpcio uses threads, not asyncio).
TTL sweeper runs in a background daemon thread.
"""
from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Literal

logger = logging.getLogger(__name__)


class NotFoundError(KeyError):
    """Requested (session_id, name) does not exist in the workspace."""


class AlreadyExistsError(KeyError):
    """A key with this (session_id, name) already exists."""


class ResourceExhaustedError(RuntimeError):
    """A per-session or global cap would be breached."""


@dataclass
class WorkspaceEntry:
    kind: Literal["df", "model"]
    data: bytes | Any          # bytes for df, opaque object for model
    size_bytes: int
    last_access: float = field(default_factory=time.monotonic)


@dataclass
class WorkspaceStatus:
    sessions: int
    dataframes: int
    models: int
    workspace_bytes: int


class Workspace:
    """Session-scoped storage for DataFrames and fitted models."""

    def __init__(
        self,
        idle_ttl_s: int = 3600,
        max_dfs_per_session: int = 50,
        max_models_per_session: int = 20,
        max_bytes_total: int = 4 * 1024 ** 3,
    ) -> None:
        self._idle_ttl_s = idle_ttl_s
        self._max_dfs = max_dfs_per_session
        self._max_models = max_models_per_session
        self._max_bytes = max_bytes_total

        # store: (session_id, name) -> WorkspaceEntry
        self._store: dict[tuple[str, str], WorkspaceEntry] = {}
        self._lock = threading.Lock()

        self._sweeper_thread: threading.Thread | None = None
        self._stop_sweeper = threading.Event()

    # ------------------------------------------------------------------
    # Public API — DataFrames
    # ------------------------------------------------------------------

    def put_df(self, session_id: str, name: str, data: bytes) -> None:
        """Store Arrow IPC bytes under (session_id, name).

        Raises:
            AlreadyExistsError: key already occupied.
            ResourceExhaustedError: per-session DF cap or total bytes cap breached.
        """
        key = (session_id, name)
        size = len(data)
        with self._lock:
            if key in self._store:
                raise AlreadyExistsError(f"df already exists: session={session_id!r} name={name!r}")
            self._check_df_cap(session_id)
            self._check_bytes_cap(size)
            self._store[key] = WorkspaceEntry(kind="df", data=data, size_bytes=size)

    def get_df(self, session_id: str, name: str) -> bytes:
        """Retrieve Arrow IPC bytes. Updates last_access timestamp.

        Raises:
            NotFoundError: key not found or entry is a model, not a df.
        """
        key = (session_id, name)
        with self._lock:
            entry = self._store.get(key)
            if entry is None or entry.kind != "df":
                raise NotFoundError(f"df not found: session={session_id!r} name={name!r}")
            entry.last_access = time.monotonic()
            return entry.data  # type: ignore[return-value]

    # ------------------------------------------------------------------
    # Public API — Models
    # ------------------------------------------------------------------

    def put_model(self, session_id: str, name: str, model: Any) -> None:
        """Store a fitted model object.

        Raises:
            AlreadyExistsError: key already occupied.
            ResourceExhaustedError: per-session model cap breached.
        """
        key = (session_id, name)
        with self._lock:
            if key in self._store:
                raise AlreadyExistsError(f"model already exists: session={session_id!r} name={name!r}")
            self._check_model_cap(session_id)
            # Models are in-memory objects — no byte counting
            self._store[key] = WorkspaceEntry(kind="model", data=model, size_bytes=0)

    def get_model(self, session_id: str, name: str) -> Any:
        """Retrieve a fitted model object.

        Raises:
            NotFoundError: key not found or entry is a df, not a model.
        """
        key = (session_id, name)
        with self._lock:
            entry = self._store.get(key)
            if entry is None or entry.kind != "model":
                raise NotFoundError(f"model not found: session={session_id!r} name={name!r}")
            entry.last_access = time.monotonic()
            return entry.data

    # ------------------------------------------------------------------
    # Public API — drop + status
    # ------------------------------------------------------------------

    def drop(self, session_id: str, name: str) -> bool:
        """Remove entry (df or model). Returns True if it existed."""
        key = (session_id, name)
        with self._lock:
            entry = self._store.pop(key, None)
            return entry is not None

    def status(self) -> WorkspaceStatus:
        """Return aggregate counts and byte totals."""
        with self._lock:
            sessions: set[str] = set()
            dfs = 0
            models = 0
            total_bytes = 0
            for (sid, _), entry in self._store.items():
                sessions.add(sid)
                if entry.kind == "df":
                    dfs += 1
                    total_bytes += entry.size_bytes
                else:
                    models += 1
            return WorkspaceStatus(
                sessions=len(sessions),
                dataframes=dfs,
                models=models,
                workspace_bytes=total_bytes,
            )

    # ------------------------------------------------------------------
    # TTL sweeper
    # ------------------------------------------------------------------

    def start_sweeper(self, interval_s: int = 300) -> None:
        """Start background daemon thread that evicts idle entries."""
        if self._sweeper_thread and self._sweeper_thread.is_alive():
            return
        self._stop_sweeper.clear()
        self._sweeper_thread = threading.Thread(
            target=self._sweep_loop,
            args=(interval_s,),
            daemon=True,
            name="metis-workspace-sweeper",
        )
        self._sweeper_thread.start()
        logger.info("Workspace sweeper started (interval=%ds, ttl=%ds)", interval_s, self._idle_ttl_s)

    def stop_sweeper(self) -> None:
        """Signal the sweeper to stop and join the thread."""
        self._stop_sweeper.set()
        if self._sweeper_thread:
            self._sweeper_thread.join(timeout=5)

    def _sweep_loop(self, interval_s: int) -> None:
        while not self._stop_sweeper.wait(timeout=interval_s):
            self._evict_expired()

    def _evict_expired(self) -> None:
        """Evict entries whose last_access is older than idle_ttl_s."""
        cutoff = time.monotonic() - self._idle_ttl_s
        with self._lock:
            expired = [k for k, e in self._store.items() if e.last_access < cutoff]
            for key in expired:
                del self._store[key]
        if expired:
            logger.info("Evicted %d expired workspace entries", len(expired))

    # ------------------------------------------------------------------
    # Internal cap checks (must be called under self._lock)
    # ------------------------------------------------------------------

    def _check_df_cap(self, session_id: str) -> None:
        count = sum(1 for (sid, _), e in self._store.items() if sid == session_id and e.kind == "df")
        if count >= self._max_dfs:
            raise ResourceExhaustedError(
                f"DF cap reached for session {session_id!r}: max={self._max_dfs}"
            )

    def _check_model_cap(self, session_id: str) -> None:
        count = sum(1 for (sid, _), e in self._store.items() if sid == session_id and e.kind == "model")
        if count >= self._max_models:
            raise ResourceExhaustedError(
                f"Model cap reached for session {session_id!r}: max={self._max_models}"
            )

    def _check_bytes_cap(self, additional_bytes: int) -> None:
        total = sum(e.size_bytes for e in self._store.values() if e.kind == "df")
        if total + additional_bytes > self._max_bytes:
            raise ResourceExhaustedError(
                f"Global byte cap would be breached: current={total} + new={additional_bytes} > max={self._max_bytes}"
            )
