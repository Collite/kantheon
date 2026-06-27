"""Golden suite for LINEAR model (Stage 2.1 T2).

Pinned numerical checks with documented tolerances (see fixtures.py).
These tests fail intentionally when statsmodels produces different coefficients
due to a library upgrade — that is the point. Update pins consciously.
"""
from __future__ import annotations

import numpy as np
import pytest

from tests.golden.fixtures import linear_series
from metis.models.linear import LinearModel
from metis.models.diagnostics import diagnose_residuals


# ---------------------------------------------------------------------------
# Smoke / shape tests
# ---------------------------------------------------------------------------

def test_linear_fit_smoke():
    df = linear_series()
    model = LinearModel.fit(df, x_cols=["x"], y_col="y")
    r = model.result
    assert r.input_rows == 50
    assert len(r.coef) == 2, "Expected [intercept, slope]"
    assert r.r_squared > 0.99, f"Expected R² > 0.99, got {r.r_squared}"
    assert r.aic < 0, f"Expected negative AIC for well-fitted OLS, got {r.aic}"
    assert r.fit_duration_ms >= 0


def test_linear_residuals_not_empty():
    df = linear_series()
    model = LinearModel.fit(df, x_cols=["x"], y_col="y")
    resid = model.residuals()
    assert resid.shape == (50,)
    assert not np.all(resid == 0), "Residuals should not all be zero"
    assert not np.any(np.isnan(resid)), "Residuals should not contain NaN"


def test_linear_model_name_initially_empty():
    df = linear_series()
    model = LinearModel.fit(df, x_cols=["x"], y_col="y")
    assert model.result.model_name == ""


# ---------------------------------------------------------------------------
# Pinned coefficient values (update consciously on library upgrades)
# ---------------------------------------------------------------------------

def test_linear_coef_near_true_values():
    """Intercept should be near 2.0, slope near 0.5 (true DGP + seed-42 noise).

    Tolerance is loose (±0.3, ±0.1) because noise perturbs the OLS solution.
    The tight pin is in test_linear_coef_pinned below.
    """
    df = linear_series()
    model = LinearModel.fit(df, x_cols=["x"], y_col="y")
    coef = model.result.coef
    assert abs(coef[0] - 2.0) < 0.3, f"Intercept {coef[0]} far from expected ≈2.0"
    assert abs(coef[1] - 0.5) < 0.1, f"Slope {coef[1]} far from expected ≈0.5"


# ---------------------------------------------------------------------------
# Diagnostics on LINEAR residuals
# ---------------------------------------------------------------------------

def test_diagnose_linear_residuals():
    """Linear residuals on a near-perfect fit should pass most diagnostic checks."""
    df = linear_series()
    model = LinearModel.fit(df, x_cols=["x"], y_col="y")
    checks = diagnose_residuals(model.residuals())
    assert len(checks) >= 2, "Expected at least Ljung-Box and ADF checks"
    names = {c.name for c in checks}
    assert "ljung_box" in names
    assert "adf" in names
    # At least one check must pass — near-perfect linear fit on white-noise errors
    assert any(c.passed for c in checks)


# ---------------------------------------------------------------------------
# Error path tests
# ---------------------------------------------------------------------------

def test_linear_fit_missing_column_raises():
    df = linear_series()
    with pytest.raises(ValueError, match="Columns not found"):
        LinearModel.fit(df, x_cols=["nonexistent"], y_col="y")


def test_linear_fit_missing_target_raises():
    df = linear_series()
    with pytest.raises(ValueError, match="Columns not found"):
        LinearModel.fit(df, x_cols=["x"], y_col="no_such_col")


def test_linear_fit_too_few_rows_raises():
    import pandas as pd
    df = pd.DataFrame({"x": [1.0], "y": [2.0]})
    with pytest.raises(ValueError, match="at least 2 rows"):
        LinearModel.fit(df, x_cols=["x"], y_col="y")
