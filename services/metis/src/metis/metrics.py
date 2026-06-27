"""Lightweight in-process metrics, rendered as Prometheus text (Stage 3.1 T2).

Dependency-free so it works in the local/CI image without pulling a metrics SDK.
A single process-wide `METRICS` registry accumulates counters and simple
duration summaries (count + sum, enough for an average and a rate); the FastAPI
`/metrics` probe renders it in Prometheus exposition format. OTLP export (the
full architecture §7 path) layers on top later without changing call sites.
"""
from __future__ import annotations

import threading
from typing import Dict, Tuple

# A label set is a sorted tuple of (key, value) pairs so it is hashable + stable.
_Labels = Tuple[Tuple[str, str], ...]


def _labelset(labels: dict[str, str]) -> _Labels:
    return tuple(sorted(labels.items()))


def _render_labels(labels: _Labels) -> str:
    if not labels:
        return ""
    inner = ",".join(f'{k}="{v}"' for k, v in labels)
    return "{" + inner + "}"


class Metrics:
    """Thread-safe counters + duration summaries with Prometheus rendering."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counters: Dict[Tuple[str, _Labels], float] = {}
        # name → labelset → (count, sum_ms)
        self._summaries: Dict[Tuple[str, _Labels], Tuple[int, float]] = {}

    def inc(self, name: str, amount: float = 1.0, **labels: str) -> None:
        key = (name, _labelset(labels))
        with self._lock:
            self._counters[key] = self._counters.get(key, 0.0) + amount

    def observe(self, name: str, value_ms: float, **labels: str) -> None:
        key = (name, _labelset(labels))
        with self._lock:
            count, total = self._summaries.get(key, (0, 0.0))
            self._summaries[key] = (count + 1, total + value_ms)

    def render(self) -> str:
        lines: list[str] = []
        with self._lock:
            counters = dict(self._counters)
            summaries = dict(self._summaries)
        for (name, labels), value in sorted(counters.items()):
            lines.append(f"{name}{_render_labels(labels)} {value}")
        for (name, labels), (count, total) in sorted(summaries.items()):
            lines.append(f"{name}_count{_render_labels(labels)} {count}")
            lines.append(f"{name}_sum{_render_labels(labels)} {total}")
        return "\n".join(lines) + ("\n" if lines else "")


# Process-wide registry.
METRICS = Metrics()
