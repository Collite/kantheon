"""
MetisService RPC implementation.

Phase 1 (Stage 1.1): workspace RPCs only.
  ImportDataFrame  — client-streaming; assembles chunks, validates, stores in workspace.
  ExportDataFrame  — server-streaming; reads from workspace, yields IPC chunks.
  DropWorkspaceEntry — removes one entry.
  GetStatus        — aggregate workspace counts.

Phase 2 (Stage 2.1+): estimation RPCs.
  Fit              — LINEAR / ARIMA / PROPHET model fitting.
  Diagnose         — Ljung-Box, ADF, residual normality checks.
  Project          — forecast with CI bands; output written as workspace DF.
  SimulateScenario — apply multiplicative/additive deltas over a forecast frame.

gRPC status code mapping:
  NotFoundError       -> NOT_FOUND
  AlreadyExistsError  -> ALREADY_EXISTS
  ResourceExhaustedError -> RESOURCE_EXHAUSTED
  ValueError          -> INVALID_ARGUMENT
  TimeoutError        -> DEADLINE_EXCEEDED
"""
from __future__ import annotations

import logging

import grpc
import pandas as pd
import pyarrow as pa

from metis.arrow_io import read_ipc_bytes, schema_fingerprint, iter_ipc_chunks, write_ipc_bytes
from metis.config import MetisConfig, load_config
from metis.metis_pb2_grpc import MetisServiceServicer
from metis.metrics import METRICS
from metis.models.fit_runner import run_with_timeout
from metis.workspace import AlreadyExistsError, NotFoundError, ResourceExhaustedError, Workspace

from org.tatrman.metis.v1 import metis_pb2 as _pb2
from org.tatrman.kantheon.common.v1 import response_message_pb2 as _common

logger = logging.getLogger(__name__)


def _abort(context: grpc.ServicerContext, code: grpc.StatusCode, detail: str) -> None:
    METRICS.inc("metis_rpc_errors_total", code=code.name)
    context.abort(code, detail)


def _msg(severity: int, code: str, human: str) -> _common.ResponseMessage:
    """Build a Rule-6 ResponseMessage (field 99 on every response)."""
    return _common.ResponseMessage(severity=severity, code=code, human_message=human)


def _info(code: str, human: str) -> _common.ResponseMessage:
    return _msg(_common.Severity.INFO, code, human)


def _warning(code: str, human: str) -> _common.ResponseMessage:
    return _msg(_common.Severity.WARNING, code, human)


def _arima_series(df: pd.DataFrame) -> pd.Series:
    """Extract the ARIMA target series, indexing by a 'ds' column when present.

    Prefers a 'y' column for the values, else the first non-'ds' column. When a
    'ds' column exists it becomes a parsed DatetimeIndex so forecasts carry real
    dates (and ISO-date horizons resolve); otherwise statsmodels uses a
    positional index and emits positional `ds` values.
    """
    value_col = "y" if "y" in df.columns else next(
        (c for c in df.columns if c != "ds"), df.columns[0]
    )
    series = df[value_col]
    if "ds" in df.columns:
        idx = pd.DatetimeIndex(pd.to_datetime(df["ds"]))
        series = pd.Series(series.to_numpy(), index=idx, name=value_col)
    return series


class MetisServiceImpl(MetisServiceServicer):
    """Concrete MetisService implementation wired to a Workspace."""

    def __init__(self, workspace: Workspace, config: MetisConfig | None = None) -> None:
        self._ws = workspace
        self._cfg = config if config is not None else load_config()

    # ------------------------------------------------------------------
    # Workspace RPCs
    # ------------------------------------------------------------------

    def ImportDataFrame(self, request_iterator, context):
        """Client-streaming: collect ArrowChunk stream, validate, store in workspace."""
        chunks: list[bytes] = []
        header: _pb2.ImportHeader | None = None

        for chunk in request_iterator:
            if header is None:
                # First chunk must carry the header
                if not chunk.HasField("header"):
                    _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                           "First chunk must carry an ImportHeader")
                    return _pb2.ImportResult()
                hdr = chunk.header
                if not hdr.session_id:
                    _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                           "ImportHeader.session_id must not be empty")
                    return _pb2.ImportResult()
                if not hdr.df_name:
                    _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                           "ImportHeader.df_name must not be empty")
                    return _pb2.ImportResult()
                header = hdr
            if chunk.ipc_payload:
                chunks.append(chunk.ipc_payload)

        if header is None:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   "Empty request stream — no chunks received")
            return _pb2.ImportResult()

        # Assemble all IPC payloads into one table
        try:
            if len(chunks) == 1:
                table = read_ipc_bytes(chunks[0])
            elif chunks:
                # Concatenate multiple IPC streams
                tables = [read_ipc_bytes(c) for c in chunks]
                table = pa.concat_tables(tables)
            else:
                # Header-only stream with no payload — import empty table from schema
                table = pa.table({})
        except Exception as exc:
            logger.warning("Failed to parse Arrow IPC payload: %s", exc)
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   f"Invalid Arrow IPC payload: {exc}")
            return _pb2.ImportResult()

        fp = schema_fingerprint(table.schema)

        # Validate schema fingerprint if provided
        if header.HasField("expected_schema_fingerprint"):
            if fp != header.expected_schema_fingerprint:
                _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                       f"Schema fingerprint mismatch: expected={header.expected_schema_fingerprint!r} got={fp!r}")
                return _pb2.ImportResult()

        # Serialise to canonical IPC bytes for storage
        ipc_bytes = write_ipc_bytes(table)

        try:
            self._ws.put_df(header.session_id, header.df_name, ipc_bytes)
        except AlreadyExistsError as exc:
            _abort(context, grpc.StatusCode.ALREADY_EXISTS, str(exc))
            return _pb2.ImportResult()
        except ResourceExhaustedError as exc:
            _abort(context, grpc.StatusCode.RESOURCE_EXHAUSTED, str(exc))
            return _pb2.ImportResult()

        logger.info("ImportDataFrame: session=%r name=%r rows=%d fp=%s",
                    header.session_id, header.df_name, table.num_rows, fp)
        return _pb2.ImportResult(
            df_name=header.df_name,
            schema_fingerprint=fp,
            rows=table.num_rows,
        )

    def ExportDataFrame(self, request, context):
        """Server-streaming: read from workspace, yield IPC chunks."""
        if not request.session_id:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   "ExportRequest.session_id must not be empty")
            return
        if not request.df_name:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   "ExportRequest.df_name must not be empty")
            return

        try:
            ipc_bytes = self._ws.get_df(request.session_id, request.df_name)
        except NotFoundError as exc:
            _abort(context, grpc.StatusCode.NOT_FOUND, str(exc))
            return

        try:
            table = read_ipc_bytes(ipc_bytes)
        except Exception as exc:
            logger.error("Failed to decode stored IPC bytes: %s", exc)
            _abort(context, grpc.StatusCode.INTERNAL, f"Corrupt stored data: {exc}")
            return

        chunk_rows = request.chunk_rows if request.chunk_rows > 0 else 65536
        for chunk_bytes in iter_ipc_chunks(table, chunk_rows=chunk_rows):
            yield _pb2.ArrowChunk(ipc_payload=chunk_bytes)

    def DropWorkspaceEntry(self, request, context):
        """Remove one workspace entry (df or model)."""
        if not request.session_id:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   "DropRequest.session_id must not be empty")
            return _pb2.DropResult()
        if not request.name:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   "DropRequest.name must not be empty")
            return _pb2.DropResult()

        existed = self._ws.drop(request.session_id, request.name)
        return _pb2.DropResult(existed=existed)

    def GetStatus(self, request, context):
        """Return aggregate workspace counts."""
        s = self._ws.status()
        return _pb2.GetStatusResponse(
            sessions=s.sessions,
            dataframes=s.dataframes,
            models=s.models,
            workspace_bytes=s.workspace_bytes,
        )

    # ------------------------------------------------------------------
    # Estimation RPCs — Phase 2
    # ------------------------------------------------------------------

    def Fit(self, request, context):
        """Fit a LINEAR / ARIMA / PROPHET model and store it in the workspace.

        Input: either *input_df* (workspace DF name) or *inline_arrow_ipc* bytes.
        Output: FitResult with timing, AIC, chosen_order (ARIMA), input_rows.
        """
        # --- Validate required fields -------------------------------------
        if not request.session_id:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "session_id required")
            return _pb2.FitResult()
        if not request.model_name:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "model_name required")
            return _pb2.FitResult()
        if request.model_kind == _pb2.ModelKind.MODEL_KIND_UNSPECIFIED:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "model_kind required")
            return _pb2.FitResult()

        # --- Resolve input table ------------------------------------------
        input_case = request.WhichOneof("input")
        if input_case == "inline_arrow_ipc":
            try:
                table = read_ipc_bytes(request.inline_arrow_ipc)
            except Exception as exc:
                _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                       f"Invalid inline Arrow IPC: {exc}")
                return _pb2.FitResult()
        elif input_case == "input_df":
            try:
                ipc_bytes = self._ws.get_df(request.session_id, request.input_df)
                table = read_ipc_bytes(ipc_bytes)
            except NotFoundError as exc:
                _abort(context, grpc.StatusCode.NOT_FOUND, str(exc))
                return _pb2.FitResult()
            except Exception as exc:
                _abort(context, grpc.StatusCode.INTERNAL,
                       f"Failed to read workspace DF: {exc}")
                return _pb2.FitResult()
        else:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   "Either input_df or inline_arrow_ipc is required")
            return _pb2.FitResult()

        # --- Guard: per-fit input-row cap (cheap pre-modelling OOM guard) ---
        if table.num_rows > self._cfg.max_fit_rows:
            _abort(
                context,
                grpc.StatusCode.RESOURCE_EXHAUSTED,
                f"Fit input has {table.num_rows} rows, exceeds cap "
                f"{self._cfg.max_fit_rows} (METIS_MAX_FIT_ROWS)",
            )
            return _pb2.FitResult()

        # --- Route to model implementation --------------------------------
        df = table.to_pandas()
        kind_name = _pb2.ModelKind.Name(request.model_kind)
        timeout_ms = self._cfg.fit_timeout_ms(kind_name)
        seed = self._cfg.seed

        model: object
        chosen_order = ""
        aic = 0.0
        log_likelihood = 0.0
        fit_duration_ms = 0
        model_warnings: list[str] = []

        try:
            if request.model_kind == _pb2.ModelKind.LINEAR:
                from metis.models.linear import LinearModel

                lp = request.linear
                x_cols = list(lp.x_cols)
                y_col = lp.y_col
                if not x_cols:
                    _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                           "LinearParams.x_cols must not be empty")
                    return _pb2.FitResult()
                if not y_col:
                    _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                           "LinearParams.y_col must not be empty")
                    return _pb2.FitResult()

                model = run_with_timeout(
                    lambda: LinearModel.fit(df, x_cols=x_cols, y_col=y_col),
                    timeout_ms, "LINEAR fit",
                )
                r = model.result  # type: ignore[attr-defined]
                aic = r.aic
                log_likelihood = r.log_likelihood
                fit_duration_ms = r.fit_duration_ms
                model_warnings = r.warnings

            elif request.model_kind == _pb2.ModelKind.ARIMA:
                from metis.models.arima import ArimaModel

                ap = request.arima
                kwargs: dict = {"seed": seed, "timeout_ms": timeout_ms}
                if ap.HasField("seasonality"):
                    kwargs["seasonality"] = ap.seasonality
                if ap.HasField("order"):
                    kwargs["order_str"] = ap.order
                if ap.HasField("max_order"):
                    kwargs["max_order"] = ap.max_order

                # Select the series column: prefer 'y', else fall back to first
                # non-'ds' column. Use a 'ds' column (if present) as the series'
                # DatetimeIndex so forecasts carry real dates and ISO-date
                # horizons resolve (otherwise statsmodels emits positional ds).
                series = _arima_series(df)

                model = run_with_timeout(
                    lambda: ArimaModel.fit(series, **kwargs),
                    timeout_ms, "ARIMA fit",
                )
                r = model.result  # type: ignore[attr-defined]
                chosen_order = r.chosen_order
                aic = r.aic
                log_likelihood = r.log_likelihood
                fit_duration_ms = r.fit_duration_ms
                model_warnings = r.warnings

            elif request.model_kind == _pb2.ModelKind.PROPHET:
                from metis.models.prophet_model import ProphetModel

                pp = request.prophet
                kwargs = {"seed": seed, "freq": self._cfg.prophet_default_freq}
                if pp.HasField("yearly"):
                    kwargs["yearly"] = pp.yearly
                if pp.HasField("weekly"):
                    kwargs["weekly"] = pp.weekly
                if pp.HasField("daily"):
                    kwargs["daily"] = pp.daily

                model = run_with_timeout(
                    lambda: ProphetModel.fit(df, **kwargs),
                    timeout_ms, "PROPHET fit",
                )
                r = model.result  # type: ignore[attr-defined]
                fit_duration_ms = r.fit_duration_ms
                model_warnings = r.warnings
                # Prophet has no AIC/log_likelihood in the statsmodels sense.
                # Contract §1.2: NaN-encoded as omitted where N/A — 0.0 is a
                # valid AIC, so emitting it would let a consumer mistake "N/A"
                # for a real zero. NaN is unambiguous.
                aic = float("nan")
                log_likelihood = float("nan")

            else:
                _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                       f"Unknown model_kind: {request.model_kind}")
                return _pb2.FitResult()

        except TimeoutError as exc:
            METRICS.inc("metis_fits_total", model_kind=kind_name, result="timeout")
            _abort(context, grpc.StatusCode.DEADLINE_EXCEEDED, str(exc))
            return _pb2.FitResult()
        except (ValueError, RuntimeError) as exc:
            METRICS.inc("metis_fits_total", model_kind=kind_name, result="error")
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            return _pb2.FitResult()
        except ImportError as exc:
            METRICS.inc("metis_fits_total", model_kind=kind_name, result="error")
            _abort(context, grpc.StatusCode.FAILED_PRECONDITION, str(exc))
            return _pb2.FitResult()

        # --- Tag the model with its name and store ------------------------
        model.result.model_name = request.model_name  # type: ignore[attr-defined]

        try:
            self._ws.put_model(request.session_id, request.model_name, model)
        except AlreadyExistsError as exc:
            _abort(context, grpc.StatusCode.ALREADY_EXISTS, str(exc))
            return _pb2.FitResult()
        except ResourceExhaustedError as exc:
            _abort(context, grpc.StatusCode.RESOURCE_EXHAUSTED, str(exc))
            return _pb2.FitResult()

        logger.info(
            "Fit: session=%r model=%r kind=%s rows=%d aic=%.2f seed=%d",
            request.session_id,
            request.model_name,
            kind_name,
            table.num_rows,
            aic,
            seed,
        )

        METRICS.inc("metis_fits_total", model_kind=kind_name, result="ok")
        METRICS.observe("metis_fit_duration_ms", float(fit_duration_ms), model_kind=kind_name)

        # Rule 6: echo the seed (reproducibility) + any non-fatal model warnings.
        messages = [_info("seed", f"fit seed = {seed}")]
        messages += [_warning("fit_warning", w) for w in model_warnings]

        return _pb2.FitResult(
            model_name=request.model_name,
            model_kind=request.model_kind,
            chosen_order=chosen_order,
            aic=aic,
            log_likelihood=log_likelihood,
            input_rows=table.num_rows,
            fit_duration_ms=fit_duration_ms,
            messages=messages,
        )

    def Diagnose(self, request, context):
        """Run statistical diagnostics on a fitted model's residuals.

        Supported model kinds: LINEAR, ARIMA.
        Prophet residuals are not directly exposed in Phase 2.
        """
        if not request.session_id:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "session_id required")
            return _pb2.DiagnoseResult()
        if not request.model_name:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "model_name required")
            return _pb2.DiagnoseResult()

        try:
            model = self._ws.get_model(request.session_id, request.model_name)
        except NotFoundError as exc:
            _abort(context, grpc.StatusCode.NOT_FOUND, str(exc))
            return _pb2.DiagnoseResult()

        # Get residuals — dispatch on model type
        from metis.models.linear import LinearModel
        from metis.models.arima import ArimaModel
        from metis.models.prophet_model import ProphetModel

        try:
            if isinstance(model, (LinearModel, ArimaModel)):
                residuals = model.residuals()
            elif isinstance(model, ProphetModel):
                _abort(context, grpc.StatusCode.UNIMPLEMENTED,
                       "Prophet residual diagnostics are not supported in Phase 2")
                return _pb2.DiagnoseResult()
            else:
                _abort(context, grpc.StatusCode.INTERNAL,
                       f"Unknown model type: {type(model).__name__}")
                return _pb2.DiagnoseResult()
        except Exception as exc:
            _abort(context, grpc.StatusCode.INTERNAL, f"Failed to get residuals: {exc}")
            return _pb2.DiagnoseResult()

        from metis.models.diagnostics import diagnose_residuals

        check_results = diagnose_residuals(residuals)

        proto_checks = []
        for c in check_results:
            check_msg = _pb2.DiagnosticCheck(
                name=c.name,
                statistic=c.statistic,
                p_value=c.p_value,
                detail=c.detail,
            )
            # "pass" is a Python keyword; set via setattr to avoid syntax errors.
            setattr(check_msg, "pass", c.passed)
            proto_checks.append(check_msg)

        overall_pass = all(c.passed for c in check_results)

        METRICS.inc("metis_diagnose_total", verdict="pass" if overall_pass else "fail")

        result_msg = _pb2.DiagnoseResult(checks=proto_checks)
        setattr(result_msg, "pass", overall_pass)
        return result_msg

    def Project(self, request, context):
        """Forecast future periods and store the output DF in the workspace.

        Horizon syntax:
          - "+N"      → N integer steps ahead.
          - ISO date  → forecast through that date at the series' frequency
            (requires the fitting series to have carried a 'ds' DatetimeIndex).

        A horizon date at or before the series end maps to FAILED_PRECONDITION.

        Output frame schema: ds (str), yhat (f64), yhat_lower (f64), yhat_upper (f64), kind (str).
        """
        if not request.session_id:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "session_id required")
            return _pb2.ProjectResult()
        if not request.model_name:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "model_name required")
            return _pb2.ProjectResult()
        if not request.horizon:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "horizon required")
            return _pb2.ProjectResult()
        if not request.output_df:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "output_df required")
            return _pb2.ProjectResult()

        confidence_level = (
            request.confidence_level if request.HasField("confidence_level") else 0.90
        )

        # --- Get model ----------------------------------------------------
        try:
            model = self._ws.get_model(request.session_id, request.model_name)
        except NotFoundError as exc:
            _abort(context, grpc.StatusCode.NOT_FOUND, str(exc))
            return _pb2.ProjectResult()

        from metis.models.arima import ArimaModel
        from metis.models.prophet_model import ProphetModel
        from metis.models.linear import LinearModel

        if isinstance(model, LinearModel):
            # LINEAR: no built-in temporal forecasting in Phase 2.
            _abort(context, grpc.StatusCode.UNIMPLEMENTED,
                   "Project is not supported for LINEAR models in Phase 2. "
                   "Use Diagnose + manually constructed future frames.")
            return _pb2.ProjectResult()
        if not isinstance(model, (ArimaModel, ProphetModel)):
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                   f"Unknown model type: {type(model).__name__}")
            return _pb2.ProjectResult()

        # --- Parse horizon ("+N" or ISO date) -----------------------------
        horizon_str = request.horizon.strip()
        if horizon_str.startswith("+"):
            try:
                horizon = int(horizon_str[1:])
            except ValueError:
                _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                       f"Invalid horizon format {horizon_str!r}: expected '+N' (e.g. '+12')")
                return _pb2.ProjectResult()
            if horizon < 1:
                _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                       f"horizon must be ≥ 1, got {horizon}")
                return _pb2.ProjectResult()
        else:
            # ISO date horizon → resolve to integer steps via the model's calendar.
            try:
                target = pd.Timestamp(horizon_str)
            except (ValueError, TypeError):
                _abort(context, grpc.StatusCode.INVALID_ARGUMENT,
                       f"Invalid horizon {horizon_str!r}: expected '+N' or an ISO date")
                return _pb2.ProjectResult()
            try:
                horizon = model.steps_to_date(target)  # type: ignore[attr-defined]
            except ValueError as exc:
                # A date at/before the series end is a precondition failure;
                # a missing/un-inferable date index is too (no calendar to walk).
                _abort(context, grpc.StatusCode.FAILED_PRECONDITION, str(exc))
                return _pb2.ProjectResult()

        # --- Run projection -----------------------------------------------
        try:
            df = model.project(horizon, confidence_level=confidence_level)  # type: ignore[attr-defined]
        except (ValueError, RuntimeError) as exc:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            return _pb2.ProjectResult()
        except Exception as exc:
            logger.exception("Project failed unexpectedly")
            _abort(context, grpc.StatusCode.INTERNAL, f"Projection error: {exc}")
            return _pb2.ProjectResult()

        # --- Store output DF in workspace ---------------------------------
        output_table = pa.Table.from_pandas(df, preserve_index=False)
        ipc_bytes = write_ipc_bytes(output_table)
        fp = schema_fingerprint(output_table.schema)

        try:
            self._ws.put_df(request.session_id, request.output_df, ipc_bytes)
        except AlreadyExistsError as exc:
            _abort(context, grpc.StatusCode.ALREADY_EXISTS, str(exc))
            return _pb2.ProjectResult()
        except ResourceExhaustedError as exc:
            _abort(context, grpc.StatusCode.RESOURCE_EXHAUSTED, str(exc))
            return _pb2.ProjectResult()

        project_kind = "ARIMA" if isinstance(model, ArimaModel) else "PROPHET"
        METRICS.inc("metis_projects_total", model_kind=project_kind)
        logger.info(
            "Project: session=%r model=%r horizon=%d output=%r rows=%d",
            request.session_id,
            request.model_name,
            horizon,
            request.output_df,
            output_table.num_rows,
        )
        return _pb2.ProjectResult(
            output_df=request.output_df,
            schema_fingerprint=fp,
            rows=output_table.num_rows,
        )

    def SimulateScenario(self, request, context):
        """Apply delta adjustments over a forecast frame and write the result to the workspace.

        Reads *forecast_df* from the workspace, applies the deltas in *deltas_json*,
        and stores the result under *output_df*.
        """
        if not request.session_id:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "session_id required")
            return _pb2.ProjectResult()
        if not request.forecast_df:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "forecast_df required")
            return _pb2.ProjectResult()
        if not request.deltas_json:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "deltas_json required")
            return _pb2.ProjectResult()
        if not request.output_df:
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, "output_df required")
            return _pb2.ProjectResult()

        # --- Get forecast DF from workspace -------------------------------
        try:
            ipc_bytes = self._ws.get_df(request.session_id, request.forecast_df)
            forecast_table = read_ipc_bytes(ipc_bytes)
        except NotFoundError as exc:
            _abort(context, grpc.StatusCode.NOT_FOUND, str(exc))
            return _pb2.ProjectResult()
        except Exception as exc:
            _abort(context, grpc.StatusCode.INTERNAL,
                   f"Failed to read forecast DF: {exc}")
            return _pb2.ProjectResult()

        # --- Apply scenario -----------------------------------------------
        from metis.models.scenario import apply_scenario, NotAForecastFrameError

        try:
            scenario_table = apply_scenario(forecast_table, request.deltas_json)
        except NotAForecastFrameError as exc:
            # The stored frame is not a forecast frame — a precondition failure.
            _abort(context, grpc.StatusCode.FAILED_PRECONDITION, str(exc))
            return _pb2.ProjectResult()
        except ValueError as exc:
            # Malformed deltas_json — a bad request argument.
            _abort(context, grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            return _pb2.ProjectResult()
        except Exception as exc:
            logger.exception("SimulateScenario failed unexpectedly")
            _abort(context, grpc.StatusCode.INTERNAL, f"Scenario error: {exc}")
            return _pb2.ProjectResult()

        # --- Store output -------------------------------------------------
        output_ipc = write_ipc_bytes(scenario_table)
        fp = schema_fingerprint(scenario_table.schema)

        try:
            self._ws.put_df(request.session_id, request.output_df, output_ipc)
        except AlreadyExistsError as exc:
            _abort(context, grpc.StatusCode.ALREADY_EXISTS, str(exc))
            return _pb2.ProjectResult()
        except ResourceExhaustedError as exc:
            _abort(context, grpc.StatusCode.RESOURCE_EXHAUSTED, str(exc))
            return _pb2.ProjectResult()

        METRICS.inc("metis_simulates_total")
        logger.info(
            "SimulateScenario: session=%r forecast=%r output=%r rows=%d",
            request.session_id,
            request.forecast_df,
            request.output_df,
            scenario_table.num_rows,
        )
        return _pb2.ProjectResult(
            output_df=request.output_df,
            schema_fingerprint=fp,
            rows=scenario_table.num_rows,
        )
