"""ARIMA model — SARIMAX via statsmodels with optional auto-order search."""
from __future__ import annotations

import re
import time
import logging
from dataclasses import dataclass, field
from typing import Optional, Tuple

import numpy as np
import pandas as pd
from statsmodels.tsa.statespace.sarimax import SARIMAX

logger = logging.getLogger(__name__)

# Default auto-order search space
DEFAULT_SEASONALITY_CANDIDATES = [4, 7, 12, 52]
DEFAULT_MAX_ORDER = 3

# Sentinel for "no AIC found"
_INF = float("inf")


@dataclass
class ArimaFitResult:
    model_name: str
    chosen_order: str           # "(p,d,q)(P,D,Q,s)" string
    aic: float
    log_likelihood: float
    input_rows: int
    fit_duration_ms: int
    _sm_result: object          # statsmodels SARIMAXResultsWrapper
    seed: int = 42
    # Non-fatal diagnostics surfaced to the caller on the wire (Rule 6).
    warnings: list[str] = field(default_factory=list)


class ArimaModel:
    """SARIMAX model via statsmodels with optional auto-order selection."""

    def __init__(self, result: ArimaFitResult) -> None:
        self._r = result

    @classmethod
    def fit(
        cls,
        series: pd.Series,
        seasonality: Optional[int] = None,
        order_str: Optional[str] = None,
        max_order: int = DEFAULT_MAX_ORDER,
        seed: int = 42,
        timeout_ms: int = 120_000,
    ) -> "ArimaModel":
        """Fit SARIMAX on *series*.

        Order resolution priority:
          1. *order_str* — explicit "(p,d,q)(P,D,Q,s)" string.
          2. *seasonality* — auto-order with the given fixed seasonality.
          3. Neither — auto-detect seasonality from candidates + auto-order.

        Raises:
            ValueError: on parse error, fit failure, or degenerate series.
        """
        if len(series) < 4:
            raise ValueError(f"Series too short for ARIMA: {len(series)} rows (need ≥ 4)")

        t0 = time.monotonic()
        warnings: list[str] = []

        if order_str is not None:
            p, d, q, P, D, Q, s = _parse_order_str(order_str)
            sm_result = _fit_sarimax(series, (p, d, q), (P, D, Q, s))
        elif seasonality is not None:
            p, d, q, P, D, Q, s, best_aic = _auto_order(series, seasonality, max_order, timeout_ms)
            if best_aic == _INF:
                warnings.append(
                    f"auto-order found no converging model for seasonality={seasonality}; "
                    "fell back to default (1,1,1)"
                )
            sm_result = _fit_sarimax(series, (p, d, q), (P, D, Q, s))
        else:
            p, d, q, P, D, Q, s, detected = _auto_detect_and_fit(series, max_order, timeout_ms)
            if not detected:
                warnings.append(
                    "auto-detect found no seasonal model with lower AIC; "
                    "fell back to non-seasonal default (1,1,1)(0,0,0,0)"
                )
            sm_result = _fit_sarimax(series, (p, d, q), (P, D, Q, s))

        if not getattr(sm_result, "mle_retvals", {}).get("converged", True):
            warnings.append("SARIMAX MLE did not fully converge; estimates may be unstable")

        dur = int((time.monotonic() - t0) * 1000)
        order_str_out = f"({p},{d},{q})({P},{D},{Q},{s})"

        r = ArimaFitResult(
            model_name="",
            chosen_order=order_str_out,
            aic=float(sm_result.aic),
            log_likelihood=float(sm_result.llf),
            input_rows=len(series),
            fit_duration_ms=dur,
            _sm_result=sm_result,
            seed=seed,
            warnings=warnings,
        )
        return cls(r)

    @property
    def result(self) -> ArimaFitResult:
        return self._r

    def residuals(self) -> np.ndarray:
        """Return SARIMAX residuals as a NumPy array."""
        return np.asarray(self._r._sm_result.resid)

    def steps_to_date(self, target: pd.Timestamp) -> int:
        """Number of forecast steps from the series end to *target* (ISO horizon).

        Requires the fitting series to have carried a DatetimeIndex with an
        inferable frequency. Returns a positive step count.

        Raises:
            ValueError: if the series has no usable date index/frequency, or
                *target* is not strictly after the last observation.
        """
        dates = getattr(self._r._sm_result.data, "dates", None)
        if dates is None or len(dates) == 0:
            raise ValueError(
                "ISO-date horizon needs a DatetimeIndex on the input series "
                "(provide a 'ds' column); use a '+N' horizon otherwise"
            )
        last = pd.Timestamp(dates[-1])
        freq = getattr(dates, "freq", None) or pd.infer_freq(dates)
        if freq is None:
            raise ValueError(
                "could not infer series frequency for an ISO-date horizon; "
                "use a '+N' horizon"
            )
        if target <= last:
            raise ValueError(
                f"horizon date {target.date()} is not after the series end {last.date()}"
            )
        rng = pd.date_range(start=last, end=target, freq=freq)
        # rng includes `last`; the number of future steps is len-1.
        steps = len(rng) - 1
        if steps < 1:
            raise ValueError(
                f"horizon date {target.date()} resolves to 0 steps at freq {freq}"
            )
        return steps

    def project(self, horizon: int, confidence_level: float = 0.90) -> pd.DataFrame:
        """Forecast *horizon* steps ahead.

        Returns a DataFrame with columns: ds, yhat, yhat_lower, yhat_upper, kind.
        """
        if horizon < 1:
            raise ValueError(f"horizon must be ≥ 1, got {horizon}")
        alpha = 1.0 - confidence_level
        forecast = self._r._sm_result.get_forecast(steps=horizon)
        summary = forecast.summary_frame(alpha=alpha)

        df = pd.DataFrame(
            {
                "ds": [str(idx) for idx in summary.index],
                "yhat": summary["mean"].to_numpy(dtype=float),
                "yhat_lower": summary["mean_ci_lower"].to_numpy(dtype=float),
                "yhat_upper": summary["mean_ci_upper"].to_numpy(dtype=float),
                "kind": ["forecast"] * horizon,
            }
        )
        return df


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _parse_order_str(s: str) -> Tuple[int, int, int, int, int, int, int]:
    """Parse '(p,d,q)(P,D,Q,s)' string into 7 ints.

    Raises:
        ValueError: if the format doesn't match.
    """
    m = re.fullmatch(r"\((\d+),(\d+),(\d+)\)\((\d+),(\d+),(\d+),(\d+)\)", s.strip())
    if not m:
        raise ValueError(
            f"Invalid ARIMA order string: {s!r}. Expected format '(p,d,q)(P,D,Q,s)'"
        )
    return tuple(int(x) for x in m.groups())  # type: ignore[return-value]


def _fit_sarimax(
    series: pd.Series,
    order: Tuple[int, int, int],
    seasonal_order: Tuple[int, int, int, int],
) -> object:
    """Fit SARIMAX and return the results wrapper.

    Raises:
        ValueError: if statsmodels raises during fit.
    """
    try:
        model = SARIMAX(
            series,
            order=order,
            seasonal_order=seasonal_order,
            enforce_stationarity=False,
            enforce_invertibility=False,
        )
        return model.fit(disp=False, maxiter=200)
    except Exception as exc:
        raise ValueError(
            f"SARIMAX fit failed for order={order} seasonal={seasonal_order}: {exc}"
        ) from exc


def _auto_order(
    series: pd.Series,
    s: int,
    max_p: int,
    timeout_ms: int,  # noqa: ARG001 — reserved for future process-pool integration
) -> Tuple[int, int, int, int, int, int, int, float]:
    """Bounded AIC search over (p, d, q) with fixed seasonality *s*.

    Returns a tuple of (p, d, q, P, D, Q, s, best_aic).
    Falls back to (1, 1, 1, 0, 0, 0, s) if nothing converges.
    """
    best_aic = _INF
    best_params: Tuple[int, int, int, int, int, int, int] = (1, 1, 1, 0, 0, 0, s)

    d = 1  # one-order differencing as default
    for p in range(0, max_p + 1):
        for q in range(0, max_p + 1):
            try:
                m = SARIMAX(
                    series,
                    order=(p, d, q),
                    seasonal_order=(0, 0, 0, s),
                    enforce_stationarity=False,
                    enforce_invertibility=False,
                )
                r = m.fit(disp=False, maxiter=100)
                if r.aic < best_aic:
                    best_aic = r.aic
                    best_params = (p, d, q, 0, 0, 0, s)
            except Exception:
                continue

    return best_params + (best_aic,)  # type: ignore[return-value]


def _auto_detect_and_fit(
    series: pd.Series,
    max_p: int,
    timeout_ms: int,
) -> Tuple[int, int, int, int, int, int, int, bool]:
    """Try each seasonality candidate; pick the combination with lowest AIC.

    Returns (p, d, q, P, D, Q, s, detected) where *detected* is True iff a
    seasonal model with a finite AIC was found. Falls back to
    (1, 1, 1, 0, 0, 0, 0) with detected=False when nothing converges.
    """
    best_aic = _INF
    best: Tuple[int, int, int, int, int, int, int] = (1, 1, 1, 0, 0, 0, 0)
    detected = False

    for s in DEFAULT_SEASONALITY_CANDIDATES:
        if len(series) < 2 * s:
            continue
        try:
            result = _auto_order(series, s, max_p, timeout_ms)
            candidate_aic = result[-1]
            if candidate_aic < best_aic:
                best_aic = candidate_aic
                best = result[:-1]  # type: ignore[assignment]
                detected = True
        except Exception:
            continue

    return best + (detected,)  # type: ignore[return-value]
