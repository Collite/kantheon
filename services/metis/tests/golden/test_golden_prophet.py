"""Golden suite for PROPHET model.

Phase 2: smoke tests only. Full numerical golden (pinned yhat) is deferred to
Phase 3 once the Stan/cmdstanpy environment is stable in CI.

Prophet is an optional dependency — tests are skipped if the package is not
installed (e.g. lightweight CI environments).
"""
from __future__ import annotations

import pytest

from tests.golden.fixtures import prophet_df

prophet = pytest.importorskip("prophet", reason="prophet package not installed — skipping Prophet golden suite")


from metis.models.prophet_model import ProphetModel  # noqa: E402  (after importorskip)


# ---------------------------------------------------------------------------
# Smoke tests
# ---------------------------------------------------------------------------

def test_prophet_fit_smoke():
    df = prophet_df(n_months=36)
    model = ProphetModel.fit(df, yearly=True, weekly=False, daily=False, seed=42)
    r = model.result
    assert r.input_rows == 36
    assert r.fit_duration_ms >= 0
    assert r.yearly_seasonality is True
    assert r.weekly_seasonality is False
    assert r.daily_seasonality is False


def test_prophet_fit_model_name_initially_empty():
    df = prophet_df(n_months=36)
    model = ProphetModel.fit(df, seed=42)
    assert model.result.model_name == ""


def test_prophet_project_shape():
    """Project produces correct output frame shape."""
    df = prophet_df(n_months=36)
    model = ProphetModel.fit(df, yearly=True, weekly=False, daily=False, seed=42)
    forecast = model.project(horizon_periods=6)
    assert len(forecast) == 6
    assert set(forecast.columns) >= {"ds", "yhat", "yhat_lower", "yhat_upper", "kind"}
    assert all(forecast["kind"] == "forecast")


def test_prophet_project_ci_ordering():
    """lower ≤ yhat ≤ upper for all forecast rows."""
    df = prophet_df(n_months=36)
    model = ProphetModel.fit(df, yearly=True, weekly=False, daily=False, seed=42)
    forecast = model.project(horizon_periods=3)
    assert all(forecast["yhat_lower"] <= forecast["yhat"])
    assert all(forecast["yhat"] <= forecast["yhat_upper"])


# ---------------------------------------------------------------------------
# Error paths
# ---------------------------------------------------------------------------

def test_prophet_fit_missing_ds_raises():
    import pandas as pd
    df = pd.DataFrame({"x": [1, 2, 3], "y": [4.0, 5.0, 6.0]})
    with pytest.raises(ValueError, match="requires 'ds'"):
        ProphetModel.fit(df, seed=42)


def test_prophet_fit_too_few_rows_raises():
    import pandas as pd
    df = pd.DataFrame({"ds": pd.date_range("2020-01-01", periods=1, freq="MS"), "y": [1.0]})
    with pytest.raises(ValueError, match="at least 2 rows"):
        ProphetModel.fit(df, seed=42)


def test_prophet_confidence_level_widens_bands():
    """A higher confidence_level produces strictly wider uncertainty bands.

    Regression guard for the review fix: confidence_level was previously
    ignored (Prophet's default 0.80 width was always used).
    """
    df = prophet_df(n_months=36)
    model = ProphetModel.fit(df, yearly=True, weekly=False, daily=False, seed=42)

    narrow = model.project(horizon_periods=6, confidence_level=0.50)
    wide = model.project(horizon_periods=6, confidence_level=0.95)

    narrow_width = (narrow["yhat_upper"] - narrow["yhat_lower"]).to_numpy()
    wide_width = (wide["yhat_upper"] - wide["yhat_lower"]).to_numpy()

    assert (wide_width > narrow_width).all()
