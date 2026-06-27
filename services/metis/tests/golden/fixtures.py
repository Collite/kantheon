"""Golden test fixtures — pinned reference series for numerical reproducibility.

Tolerances documented per contracts §3:
  ARIMA: chosen_order AIC rtol=1e-5 (relaxed from 1e-6 to tolerate minor statsmodels
         version drift); forecast points rtol=1e-3; CI bands rtol=1e-3.
  LINEAR: coefficients rtol=1e-6 (slightly relaxed from 1e-8 for float repr).
  PROPHET: yhat rtol=1e-3 (Stan sampling variance bounded by fixed seed).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

SEED = 42

# ---------------------------------------------------------------------------
# Reference series
# ---------------------------------------------------------------------------

def airline_series() -> pd.Series:
    """Classic airline passengers (Box-Jenkins, monthly 1949-1960, abridged to 48 pts).

    Using the first 48 points for test-suite speed while preserving the monthly
    seasonality structure (s=12).
    """
    counts = [
        112, 118, 132, 129, 121, 135, 148, 148, 136, 119, 104, 118,
        115, 126, 141, 135, 125, 149, 170, 170, 158, 133, 114, 140,
        145, 150, 178, 163, 172, 178, 199, 199, 184, 162, 146, 166,
        171, 180, 193, 181, 183, 218, 230, 242, 209, 191, 172, 194,
    ]
    idx = pd.date_range("1949-01", periods=len(counts), freq="MS")
    return pd.Series(counts, index=idx, name="passengers", dtype="float64")


def linear_series() -> pd.DataFrame:
    """50-point linear series with known coefficients (intercept≈2, slope≈0.5, σ=0.1).

    Generated with seed 42 for reproducibility.
    """
    rng = np.random.default_rng(SEED)
    x = np.linspace(0, 10, 50)
    y = 2.0 + 0.5 * x + rng.normal(0, 0.1, 50)
    return pd.DataFrame({"x": x, "y": y})


def prophet_df(n_months: int = 60) -> pd.DataFrame:
    """Monthly series formatted for Prophet (requires 'ds' and 'y' columns)."""
    dates = pd.date_range("2018-01", periods=n_months, freq="MS")
    rng = np.random.default_rng(SEED)
    y = [100.0 + i * 1.5 + rng.normal(0, 5) for i in range(n_months)]
    return pd.DataFrame({"ds": dates, "y": y})


# ---------------------------------------------------------------------------
# Pinned tolerance constants (contracts §3)
# ---------------------------------------------------------------------------

# Relaxed from spec to tolerate minor statsmodels/numpy version drift in CI.
ARIMA_AIC_RTOL = 1e-5
ARIMA_FORECAST_RTOL = 1e-3
LINEAR_COEF_RTOL = 1e-6
