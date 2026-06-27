"""SimulateScenario — apply additive/multiplicative deltas to a forecast frame."""
from __future__ import annotations

import json
import logging

import pyarrow as pa

logger = logging.getLogger(__name__)

# Required columns in a forecast frame
_REQUIRED_COLS = {"ds", "yhat", "yhat_lower", "yhat_upper", "kind"}


class NotAForecastFrameError(ValueError):
    """Raised when the input frame is not a forecast frame (missing the required columns).

    Distinct from a plain ValueError (bad JSON) so the gRPC layer can map this
    to FAILED_PRECONDITION (a precondition on the input frame) rather than
    INVALID_ARGUMENT (a malformed request).
    """


def validate_forecast_schema(table: pa.Table) -> None:
    """Raise NotAForecastFrameError if *table* is missing any required forecast column.

    Required: ds, yhat, yhat_lower, yhat_upper, kind.
    """
    actual = set(table.schema.names)
    missing = _REQUIRED_COLS - actual
    if missing:
        raise NotAForecastFrameError(
            f"Forecast DataFrame is missing required columns: {sorted(missing)}. "
            f"Got: {sorted(actual)}"
        )


def apply_scenario(forecast_table: pa.Table, deltas_json: str) -> pa.Table:
    """Apply a delta dict to a forecast frame and return a new Arrow table.

    *deltas_json* is a Rule 7 JSON string (camelCase keys):
      - "yhatDelta":   absolute additive shift applied to yhat, yhat_lower, yhat_upper.
      - "scaleFactor": multiplicative scale applied to yhat, yhat_lower, yhat_upper
                       before the additive shift (i.e. new = old * scaleFactor + yhatDelta).
    Unknown keys are ignored with a warning.

    The output table has the same schema as the input; the "kind" column is set to
    "scenario" for all rows.

    Raises:
        ValueError: if *deltas_json* is not valid JSON or the table is missing required columns.
    """
    validate_forecast_schema(forecast_table)

    try:
        deltas: dict = json.loads(deltas_json)
    except json.JSONDecodeError as exc:
        raise ValueError(f"deltas_json is not valid JSON: {exc}") from exc

    if not isinstance(deltas, dict):
        raise ValueError(f"deltas_json must be a JSON object, got {type(deltas).__name__}")

    known_keys = {"yhatDelta", "scaleFactor"}
    unknown = set(deltas.keys()) - known_keys
    if unknown:
        logger.warning("SimulateScenario: ignoring unknown delta keys: %s", sorted(unknown))

    yhat_delta = float(deltas.get("yhatDelta", 0.0))
    scale = float(deltas.get("scaleFactor", 1.0))

    df_dict = forecast_table.to_pydict()

    yhat_new = [v * scale + yhat_delta for v in df_dict["yhat"]]
    yhat_lower_new = [v * scale + yhat_delta for v in df_dict["yhat_lower"]]
    yhat_upper_new = [v * scale + yhat_delta for v in df_dict["yhat_upper"]]

    out: dict = {
        "ds": df_dict["ds"],
        "yhat": yhat_new,
        "yhat_lower": yhat_lower_new,
        "yhat_upper": yhat_upper_new,
        "kind": ["scenario"] * len(yhat_new),
    }
    return pa.table(out)
