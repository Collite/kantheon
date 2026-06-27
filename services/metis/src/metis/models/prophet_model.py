"""PROPHET model — Facebook Prophet with fixed seed via cmdstanpy."""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

import pandas as pd

logger = logging.getLogger(__name__)


@dataclass
class ProphetFitResult:
    model_name: str
    input_rows: int
    fit_duration_ms: int
    yearly_seasonality: bool
    weekly_seasonality: bool
    daily_seasonality: bool
    seed: int = 42
    freq: str = "MS"
    # Non-fatal diagnostics surfaced to the caller on the wire (Rule 6).
    warnings: list[str] = field(default_factory=list)


class ProphetModel:
    """Facebook Prophet model wrapper."""

    def __init__(self, result: ProphetFitResult, model: object) -> None:
        self._r = result
        self._m = model     # the fitted Prophet instance

    @classmethod
    def fit(
        cls,
        df: pd.DataFrame,
        yearly: bool = True,
        weekly: bool = False,
        daily: bool = False,
        seed: int = 42,
        freq: str = "MS",
    ) -> "ProphetModel":
        """Fit Prophet on *df*.

        *df* must contain a 'ds' column (datetime-like) and a 'y' column (numeric).
        *seed* is passed to cmdstanpy for reproducibility. *freq* is the pandas
        offset alias used to build future frames at projection time; when the
        series cadence is inferable it is used in preference to *freq*.

        Raises:
            ImportError: if the `prophet` package is not installed.
            ValueError: if required columns are missing or fewer than 2 rows.
        """
        try:
            from prophet import Prophet
        except ImportError as exc:
            raise ImportError(
                "prophet package is required for PROPHET model kind. "
                "Install with: pip install prophet"
            ) from exc

        if "ds" not in df.columns or "y" not in df.columns:
            raise ValueError(
                "Prophet requires 'ds' (datetime) and 'y' (numeric) columns; "
                f"got columns: {list(df.columns)}"
            )
        if len(df) < 2:
            raise ValueError(f"Need at least 2 rows for Prophet, got {len(df)}")

        warnings: list[str] = []

        # Prefer the series' own cadence; fall back to the configured default.
        resolved_freq = freq
        try:
            inferred = pd.infer_freq(pd.to_datetime(df["ds"]))
            if inferred:
                resolved_freq = inferred
            else:
                warnings.append(
                    f"could not infer series frequency; using default freq '{freq}' "
                    "for future frames"
                )
        except Exception:
            warnings.append(
                f"could not infer series frequency; using default freq '{freq}' "
                "for future frames"
            )

        t0 = time.monotonic()

        m = Prophet(
            yearly_seasonality=yearly,
            weekly_seasonality=weekly,
            daily_seasonality=daily,
        )
        # Pass seed to Stan sampler for reproducibility
        try:
            m.fit(df, seed=seed)
        except TypeError:
            # Older Prophet versions don't accept seed kwarg
            m.fit(df)

        dur = int((time.monotonic() - t0) * 1000)
        r = ProphetFitResult(
            model_name="",
            input_rows=len(df),
            fit_duration_ms=dur,
            yearly_seasonality=yearly,
            weekly_seasonality=weekly,
            daily_seasonality=daily,
            seed=seed,
            freq=resolved_freq,
            warnings=warnings,
        )
        return cls(r, m)

    @property
    def result(self) -> ProphetFitResult:
        return self._r

    def last_date(self) -> pd.Timestamp:
        """The last observed timestamp (for ISO-date horizon resolution)."""
        return pd.Timestamp(self._m.history_dates.max())

    def steps_to_date(self, target: pd.Timestamp) -> int:
        """Number of future periods from the series end to *target* at the fit freq.

        Raises:
            ValueError: if *target* is not strictly after the last observation.
        """
        last = self.last_date()
        if target <= last:
            raise ValueError(
                f"horizon date {target.date()} is not after the series end {last.date()}"
            )
        rng = pd.date_range(start=last, end=target, freq=self._r.freq)
        steps = len(rng) - 1
        if steps < 1:
            raise ValueError(
                f"horizon date {target.date()} resolves to 0 periods at freq {self._r.freq}"
            )
        return steps

    def project(
        self,
        horizon_periods: int,
        confidence_level: float = 0.90,
    ) -> pd.DataFrame:
        """Forecast *horizon_periods* future periods at the fitted frequency.

        Returns a DataFrame with columns: ds, yhat, yhat_lower, yhat_upper, kind.
        The requested *confidence_level* is honoured: Prophet reads
        `interval_width` when it computes uncertainty intervals at predict time,
        so it is set here (no refit needed) to produce the requested band width.
        """
        # Prophet's `predict()` reads `self.interval_width` when computing the
        # uncertainty bands, so mutating it before predict gives the requested
        # CI without re-fitting the model.
        self._m.interval_width = confidence_level

        future = self._m.make_future_dataframe(
            periods=horizon_periods, freq=self._r.freq
        )
        forecast = self._m.predict(future)

        out = forecast.tail(horizon_periods)[
            ["ds", "yhat", "yhat_lower", "yhat_upper"]
        ].copy()
        out["ds"] = out["ds"].astype(str)
        out["kind"] = "forecast"
        return out.reset_index(drop=True)
