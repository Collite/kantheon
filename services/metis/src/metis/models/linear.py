"""LINEAR model — OLS via statsmodels."""
from __future__ import annotations

import time
from dataclasses import dataclass, field

import numpy as np
import pandas as pd
import statsmodels.api as sm


@dataclass
class LinearFitResult:
    model_name: str
    coef: list[float]       # [intercept, ...slopes]
    r_squared: float
    aic: float
    log_likelihood: float
    input_rows: int
    fit_duration_ms: int
    x_cols: list[str]
    y_col: str
    _sm_result: object      # statsmodels RegressionResultsWrapper, for residuals in diagnose
    seed: int = 42
    # Non-fatal diagnostics surfaced to the caller on the wire (Rule 6).
    warnings: list[str] = field(default_factory=list)


class LinearModel:
    """OLS linear regression via statsmodels."""

    def __init__(self, result: LinearFitResult) -> None:
        self._r = result

    @classmethod
    def fit(cls, df: pd.DataFrame, x_cols: list[str], y_col: str) -> "LinearModel":
        """Fit OLS on *df* with predictor columns *x_cols* and target *y_col*.

        A constant (intercept) is automatically prepended.

        Raises:
            ValueError: if required columns are missing or fewer than 2 rows.
        """
        missing = [c for c in x_cols + [y_col] if c not in df.columns]
        if missing:
            raise ValueError(f"Columns not found in DataFrame: {missing}")
        if len(df) < 2:
            raise ValueError(f"Need at least 2 rows for OLS, got {len(df)}")

        t0 = time.monotonic()
        X = sm.add_constant(df[x_cols].values, has_constant="add")
        y = df[y_col].values
        sm_res = sm.OLS(y, X).fit()
        dur = int((time.monotonic() - t0) * 1000)

        r = LinearFitResult(
            model_name="",      # set by caller after construction
            coef=sm_res.params.tolist(),
            r_squared=float(sm_res.rsquared),
            aic=float(sm_res.aic),
            log_likelihood=float(sm_res.llf),
            input_rows=len(df),
            fit_duration_ms=dur,
            x_cols=list(x_cols),
            y_col=y_col,
            _sm_result=sm_res,
        )
        return cls(r)

    @property
    def result(self) -> LinearFitResult:
        return self._r

    def residuals(self) -> np.ndarray:
        """Return OLS residuals as a NumPy array."""
        return np.asarray(self._r._sm_result.resid)
