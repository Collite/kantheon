"""Golden suite for ARIMA model (Stage 2.1 T3-T4).

Pinned numerical checks with documented tolerances (see fixtures.py).
Update pinned values consciously on library upgrades.
"""
from __future__ import annotations

import pandas as pd
import pytest

from tests.golden.fixtures import airline_series
from metis.models.arima import ArimaModel, _parse_order_str
from metis.models.diagnostics import diagnose_residuals


# ---------------------------------------------------------------------------
# Explicit-order fit
# ---------------------------------------------------------------------------

def test_arima_explicit_order_fit():
    """Explicit (1,1,1)(0,1,1,12) order on airline series: smoke + basic bounds."""
    series = airline_series()
    model = ArimaModel.fit(series, order_str="(1,1,1)(0,1,1,12)", seed=42)
    r = model.result
    assert r.chosen_order == "(1,1,1)(0,1,1,12)"
    assert r.input_rows == 48
    assert r.aic < 600, f"AIC too high: {r.aic}"
    assert r.fit_duration_ms >= 0


def test_arima_explicit_order_shape_and_type():
    series = airline_series()
    model = ArimaModel.fit(series, order_str="(1,1,1)(0,0,0,0)", seed=42)
    resid = model.residuals()
    assert len(resid) == len(series)
    assert not any(v != v for v in resid[:10]), "NaN in first 10 residuals"


# ---------------------------------------------------------------------------
# Auto-order with known seasonality
# ---------------------------------------------------------------------------

def test_arima_auto_order_with_seasonality():
    """Auto-order with known seasonality=12 on airline series."""
    series = airline_series()
    model = ArimaModel.fit(series, seasonality=12, max_order=2, seed=42)
    r = model.result
    # Should produce a valid "(p,d,q)(P,D,Q,s)" string: 2 commas in first group + 3 in second = 5
    assert r.chosen_order.count(",") == 5, f"Malformed order: {r.chosen_order!r}"
    assert r.aic < 1500, f"AIC too high for auto-order: {r.aic}"


# ---------------------------------------------------------------------------
# Project (forecast)
# ---------------------------------------------------------------------------

def test_arima_project_shape():
    """Project produces correct DataFrame shape."""
    series = airline_series()
    model = ArimaModel.fit(series, order_str="(1,1,1)(0,1,1,12)", seed=42)
    df = model.project(horizon=12, confidence_level=0.90)
    assert len(df) == 12
    assert set(df.columns) >= {"ds", "yhat", "yhat_lower", "yhat_upper", "kind"}
    assert all(df["kind"] == "forecast")


def test_arima_project_ci_ordering():
    """lower ≤ yhat ≤ upper for all forecast rows."""
    series = airline_series()
    model = ArimaModel.fit(series, order_str="(1,1,1)(0,1,1,12)", seed=42)
    df = model.project(horizon=6, confidence_level=0.90)
    assert all(df["yhat_lower"] <= df["yhat"]), "yhat_lower > yhat in some rows"
    assert all(df["yhat"] <= df["yhat_upper"]), "yhat > yhat_upper in some rows"


def test_arima_project_zero_horizon_raises():
    series = airline_series()
    model = ArimaModel.fit(series, order_str="(1,1,1)(0,0,0,0)", seed=42)
    with pytest.raises(ValueError, match="horizon must be ≥ 1"):
        model.project(horizon=0)


# ---------------------------------------------------------------------------
# Diagnostics on ARIMA residuals
# ---------------------------------------------------------------------------

def test_arima_diagnose_residuals():
    """ARIMA residuals on a reasonable model pass at least some checks."""
    series = airline_series()
    model = ArimaModel.fit(series, order_str="(1,1,1)(0,1,1,12)", seed=42)
    checks = diagnose_residuals(model.residuals())
    assert len(checks) >= 2
    names = {c.name for c in checks}
    assert "ljung_box" in names
    assert "adf" in names


# ---------------------------------------------------------------------------
# Error paths
# ---------------------------------------------------------------------------

def test_arima_invalid_order_string():
    series = airline_series()
    with pytest.raises(ValueError, match="Invalid ARIMA order string"):
        ArimaModel.fit(series, order_str="bad_format", seed=42)


def test_arima_invalid_order_string_partial():
    series = airline_series()
    with pytest.raises(ValueError, match="Invalid ARIMA order string"):
        ArimaModel.fit(series, order_str="(1,1,1)", seed=42)  # Missing seasonal part


def test_arima_too_short_series_raises():
    series = pd.Series([1.0, 2.0, 3.0])
    with pytest.raises(ValueError, match="too short"):
        ArimaModel.fit(series, order_str="(1,1,1)(0,0,0,0)", seed=42)


def test_arima_constant_series_behaviour():
    """Constant series with d=1 differencing results in zeros — statsmodels may raise or warn.

    We only verify that if it succeeds, the model is stored (no crash); if it fails,
    a ValueError is raised (not an unchecked exception).
    """
    series = pd.Series([5.0] * 30)
    try:
        model = ArimaModel.fit(series, order_str="(0,1,0)(0,0,0,0)", seed=42)
        # If it somehow fits, at least the result type is correct
        assert model.result.input_rows == 30
    except ValueError:
        pass  # expected — SARIMAX may reject this


# ---------------------------------------------------------------------------
# parse_order_str unit tests
# ---------------------------------------------------------------------------

def test_parse_order_str_valid():
    result = _parse_order_str("(1,1,1)(0,1,1,12)")
    assert result == (1, 1, 1, 0, 1, 1, 12)


def test_parse_order_str_with_spaces():
    result = _parse_order_str("  (2,1,2)(0,0,0,0)  ")
    assert result == (2, 1, 2, 0, 0, 0, 0)


def test_parse_order_str_invalid():
    with pytest.raises(ValueError):
        _parse_order_str("1,1,1")
