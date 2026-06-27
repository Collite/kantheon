"""Statistical diagnostics: Ljung-Box, ADF, and residual normality checks."""
from __future__ import annotations

import logging
from dataclasses import dataclass

import numpy as np

logger = logging.getLogger(__name__)


@dataclass
class CheckResult:
    name: str
    passed: bool
    statistic: float
    p_value: float
    detail: str


def diagnose_residuals(residuals: np.ndarray, alpha: float = 0.05) -> list[CheckResult]:
    """Run Ljung-Box, ADF, and Jarque-Bera normality checks on model residuals.

    Interpretation:
      - Ljung-Box: p > alpha → no significant autocorrelation → residuals look like white noise → pass.
      - ADF: p < alpha → reject unit root → residuals are stationary → pass.
      - Residual normality (Jarque-Bera): p > alpha → cannot reject normality → pass.

    Returns an empty list only if there are fewer than 10 clean residuals (a
    "too_few_residuals" sentinel is returned instead).
    """
    resid = residuals[~np.isnan(residuals)]

    if len(resid) < 10:
        return [
            CheckResult(
                name="too_few_residuals",
                passed=False,
                statistic=0.0,
                p_value=0.0,
                detail=f"Only {len(resid)} non-NaN residuals (need ≥ 10)",
            )
        ]

    results: list[CheckResult] = []

    # --- Ljung-Box -----------------------------------------------------------
    try:
        from statsmodels.stats.diagnostic import acorr_ljungbox  # type: ignore[import]

        lag = min(10, len(resid) // 5)
        lbq = acorr_ljungbox(resid, lags=[lag], return_df=True)
        lb_stat = float(lbq["lb_stat"].iloc[-1])
        lb_pval = float(lbq["lb_pvalue"].iloc[-1])
        results.append(
            CheckResult(
                name="ljung_box",
                passed=lb_pval > alpha,
                statistic=lb_stat,
                p_value=lb_pval,
                detail=f"lag={lag} stat={lb_stat:.4f} p={lb_pval:.4f}",
            )
        )
    except Exception as exc:  # pragma: no cover
        logger.warning("Ljung-Box check failed: %s", exc)
        results.append(CheckResult("ljung_box", False, 0.0, 0.0, f"error: {exc}"))

    # --- ADF -----------------------------------------------------------------
    try:
        from statsmodels.tsa.stattools import adfuller  # type: ignore[import]

        adf_stat, adf_pval, *_ = adfuller(resid)
        results.append(
            CheckResult(
                name="adf",
                passed=float(adf_pval) < alpha,
                statistic=float(adf_stat),
                p_value=float(adf_pval),
                detail=f"stat={adf_stat:.4f} p={adf_pval:.4f}",
            )
        )
    except Exception as exc:  # pragma: no cover
        logger.warning("ADF check failed: %s", exc)
        results.append(CheckResult("adf", False, 0.0, 0.0, f"error: {exc}"))

    # --- Residual normality (Jarque-Bera) ------------------------------------
    try:
        from scipy import stats as scipy_stats  # type: ignore[import]

        jb_stat, jb_pval = scipy_stats.jarque_bera(resid)
        results.append(
            CheckResult(
                name="residual_normality",
                passed=float(jb_pval) > alpha,
                statistic=float(jb_stat),
                p_value=float(jb_pval),
                detail=f"jarque_bera stat={jb_stat:.4f} p={jb_pval:.4f}",
            )
        )
    except Exception as exc:  # pragma: no cover
        logger.warning("Residual normality check failed: %s", exc)
        results.append(
            CheckResult("residual_normality", False, 0.0, 0.0, f"error: {exc}")
        )

    return results
