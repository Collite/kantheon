"""Runtime configuration for Metis estimation.

Centralises the knobs the plan calls for: the global seed (Stage 2.1 T7), the
per-kind fit timeouts (Stage 2.1 T6 → `DEADLINE_EXCEEDED`), the per-fit input-row
cap (Stage 2.3 T3 → `RESOURCE_EXHAUSTED`), and the Prophet default forecast
frequency. All are environment-overridable so deployments can tune without a
rebuild; defaults are safe for local + CI.
"""
from __future__ import annotations

import os
from dataclasses import dataclass


def _int_env(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class MetisConfig:
    """Estimation runtime config, resolved once at process start."""

    seed: int = 42
    # Per-kind fit deadlines (milliseconds), per contracts.md §5. A fit exceeding
    # its deadline maps to DEADLINE_EXCEEDED. Prophet (Stan/cmdstan over long
    # series) is the slow path and gets the largest budget; ARIMA's auto-order
    # search is next; LINEAR is sub-second.
    fit_timeout_ms_linear: int = 10_000
    fit_timeout_ms_arima: int = 120_000
    fit_timeout_ms_prophet: int = 300_000
    # A fit input larger than this many rows is rejected with RESOURCE_EXHAUSTED
    # before any modelling work starts (cheap guard against OOM on a huge frame).
    max_fit_rows: int = 5_000_000
    # Default frequency for Prophet future frames when the series carries no
    # inferable cadence (pandas offset alias; "MS" = month start).
    prophet_default_freq: str = "MS"

    def fit_timeout_ms(self, model_kind_name: str) -> int:
        return {
            "LINEAR": self.fit_timeout_ms_linear,
            "ARIMA": self.fit_timeout_ms_arima,
            "PROPHET": self.fit_timeout_ms_prophet,
        }.get(model_kind_name, self.fit_timeout_ms_arima)


def load_config() -> MetisConfig:
    """Resolve configuration from the environment (with safe defaults)."""
    return MetisConfig(
        seed=_int_env("METIS_SEED", 42),
        fit_timeout_ms_linear=_int_env("METIS_FIT_TIMEOUT_MS_LINEAR", 10_000),
        fit_timeout_ms_arima=_int_env("METIS_FIT_TIMEOUT_MS_ARIMA", 120_000),
        fit_timeout_ms_prophet=_int_env("METIS_FIT_TIMEOUT_MS_PROPHET", 300_000),
        max_fit_rows=_int_env("METIS_MAX_FIT_ROWS", 5_000_000),
        prophet_default_freq=os.environ.get("METIS_PROPHET_DEFAULT_FREQ", "MS"),
    )
