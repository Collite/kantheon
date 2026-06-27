#!/usr/bin/env python3
"""Fit/project micro-benchmark for Metis cost_hints (Stage 3.2 T4).

Times `fit` and `project` per model kind on 1e2 / 1e3 / 1e4-row series and
reports p50/p95 latency. The numbers feed the `cost_hints.typical_latency_ms`
in `tools/metis-mcp/.../manifests/tools/*.yaml`. Re-run after a dependency bump:

    uv run python bench/bench_fit.py

Prophet is timed only when installed (it dominates wall-clock; cmdstan compile
is excluded — the model is pre-fit once before timing). Results are indicative,
single-host; the manifest hints are rounded conservatively upward.
"""
from __future__ import annotations

import statistics
import time

import numpy as np
import pandas as pd

from metis.models.arima import ArimaModel
from metis.models.linear import LinearModel

ROW_COUNTS = [100, 1_000, 10_000]
REPEATS = 5


def _series(n: int) -> pd.Series:
    rng = np.random.default_rng(42)
    idx = pd.date_range("2000-01-01", periods=n, freq="D")
    vals = 100.0 + np.arange(n) * 0.5 + rng.normal(0, 5, n)
    return pd.Series(vals, index=idx, name="y")


def _linear_df(n: int) -> pd.DataFrame:
    rng = np.random.default_rng(42)
    x = np.linspace(0, 100, n)
    y = 2.0 + 0.5 * x + rng.normal(0, 1, n)
    return pd.DataFrame({"x": x, "y": y})


def _time(fn, repeats: int = REPEATS) -> tuple[float, float]:
    samples = []
    for _ in range(repeats):
        t0 = time.monotonic()
        fn()
        samples.append((time.monotonic() - t0) * 1000.0)
    samples.sort()
    p50 = statistics.median(samples)
    p95 = samples[min(len(samples) - 1, int(0.95 * len(samples)))]
    return p50, p95


def main() -> int:
    print(f"{'kind':<8} {'op':<8} {'rows':>7} {'p50_ms':>10} {'p95_ms':>10}")
    print("-" * 48)

    for n in ROW_COUNTS:
        ldf = _linear_df(n)
        p50, p95 = _time(lambda ldf=ldf: LinearModel.fit(ldf, ["x"], "y"))
        print(f"{'LINEAR':<8} {'fit':<8} {n:>7} {p50:>10.2f} {p95:>10.2f}")

    for n in ROW_COUNTS:
        s = _series(n)
        p50, p95 = _time(lambda s=s: ArimaModel.fit(s, order_str="(1,1,1)(0,0,0,0)"))
        print(f"{'ARIMA':<8} {'fit':<8} {n:>7} {p50:>10.2f} {p95:>10.2f}")
        m = ArimaModel.fit(s, order_str="(1,1,1)(0,0,0,0)")
        p50, p95 = _time(lambda m=m: m.project(30))
        print(f"{'ARIMA':<8} {'project':<8} {n:>7} {p50:>10.2f} {p95:>10.2f}")

    # ARIMA auto-order (the slow path) at the mid size.
    s = _series(1_000)
    p50, p95 = _time(lambda: ArimaModel.fit(s, seasonality=7, max_order=2), repeats=3)
    print(f"{'ARIMA':<8} {'auto':<8} {1000:>7} {p50:>10.2f} {p95:>10.2f}")

    try:
        from metis.models.prophet_model import ProphetModel

        for n in (100, 1_000):
            pdf = pd.DataFrame({"ds": _series(n).index, "y": _series(n).to_numpy()})
            p50, p95 = _time(lambda pdf=pdf: ProphetModel.fit(pdf), repeats=3)
            print(f"{'PROPHET':<8} {'fit':<8} {n:>7} {p50:>10.2f} {p95:>10.2f}")
    except ImportError:
        print("PROPHET  (skipped — prophet not installed)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
