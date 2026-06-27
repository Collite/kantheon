"""Stage 2.1/2.2/2.3 component tests: estimation RPCs over in-process gRPC.

Tests cover:
  Fit   — LINEAR and ARIMA (PROPHET is integration-heavy, skipped here)
  Diagnose — after LINEAR fit
  Project  — after ARIMA fit
  SimulateScenario — after Project
"""
from __future__ import annotations

import numpy as np
import pytest
import grpc
import pyarrow as pa

from org.tatrman.metis.v1 import metis_pb2
from metis.arrow_io import write_ipc_bytes, read_ipc_bytes


# ---------------------------------------------------------------------------
# Test-data factories
# ---------------------------------------------------------------------------

def _make_linear_ipc(n: int = 50) -> bytes:
    rng = np.random.default_rng(42)
    x = np.linspace(0, 10, n)
    y = 2.0 + 0.5 * x + rng.normal(0, 0.1, n)
    table = pa.table(
        {"x": pa.array(x, type=pa.float64()), "y": pa.array(y, type=pa.float64())}
    )
    return write_ipc_bytes(table)


def _make_arima_ipc(n: int = 48) -> bytes:
    """Monthly-ish series suitable for SARIMAX(1,1,1)(0,0,0,0)."""
    rng = np.random.default_rng(42)
    vals = [100.0 + i * 2 + rng.normal(0, 5) for i in range(n)]
    table = pa.table({"y": pa.array(vals, type=pa.float64())})
    return write_ipc_bytes(table)


def _import_df(stub, session_id: str, df_name: str, ipc_bytes: bytes) -> None:
    """Helper: import an IPC DataFrame via ImportDataFrame streaming RPC."""
    chunk = metis_pb2.ArrowChunk(
        header=metis_pb2.ImportHeader(session_id=session_id, df_name=df_name),
        ipc_payload=ipc_bytes,
    )
    stub.ImportDataFrame(iter([chunk]))


# ---------------------------------------------------------------------------
# LINEAR Fit
# ---------------------------------------------------------------------------

def test_fit_linear_inline(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    ipc = _make_linear_ipc()
    req = metis_pb2.FitRequest(
        session_id="s_linear",
        model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=ipc,
        model_name="linear_m",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    )
    res = stub.Fit(req)
    assert res.model_name == "linear_m"
    assert res.model_kind == metis_pb2.ModelKind.LINEAR
    assert res.input_rows == 50
    assert res.fit_duration_ms >= 0
    # LINEAR has AIC (statsmodels OLS)
    assert res.aic != 0.0


def test_fit_linear_from_workspace_df(grpc_server_and_stub):
    """Fit using a pre-imported workspace DF (input_df path)."""
    _, stub = grpc_server_and_stub
    ipc = _make_linear_ipc()
    _import_df(stub, "s_ws", "df_linear", ipc)

    req = metis_pb2.FitRequest(
        session_id="s_ws",
        model_kind=metis_pb2.ModelKind.LINEAR,
        input_df="df_linear",
        model_name="linear_from_ws",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    )
    res = stub.Fit(req)
    assert res.model_name == "linear_from_ws"
    assert res.input_rows == 50


def test_fit_missing_session_id_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.FitRequest(
        session_id="",
        model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=_make_linear_ipc(),
        model_name="m1",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.Fit(req)
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_fit_missing_model_name_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.FitRequest(
        session_id="s1",
        model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=_make_linear_ipc(),
        model_name="",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.Fit(req)
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_fit_unspecified_model_kind_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.FitRequest(
        session_id="s1",
        model_kind=metis_pb2.ModelKind.MODEL_KIND_UNSPECIFIED,
        inline_arrow_ipc=_make_linear_ipc(),
        model_name="m1",
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.Fit(req)
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_fit_duplicate_model_name_raises_already_exists(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.FitRequest(
        session_id="s_dup",
        model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=_make_linear_ipc(),
        model_name="m_dup",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    )
    stub.Fit(req)   # first fit OK
    with pytest.raises(grpc.RpcError) as exc:
        stub.Fit(req)   # second fit → ALREADY_EXISTS
    assert exc.value.code() == grpc.StatusCode.ALREADY_EXISTS


def test_fit_unknown_input_df_raises_not_found(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.FitRequest(
        session_id="s_nodf",
        model_kind=metis_pb2.ModelKind.LINEAR,
        input_df="nonexistent_df",
        model_name="m1",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.Fit(req)
    assert exc.value.code() == grpc.StatusCode.NOT_FOUND


def test_fit_linear_missing_x_cols_raises(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.FitRequest(
        session_id="s_nocol",
        model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=_make_linear_ipc(),
        model_name="m1",
        linear=metis_pb2.LinearParams(x_cols=[], y_col="y"),
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.Fit(req)
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


# ---------------------------------------------------------------------------
# ARIMA Fit
# ---------------------------------------------------------------------------

def test_fit_arima_explicit_order_inline(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    ipc = _make_arima_ipc()
    req = metis_pb2.FitRequest(
        session_id="s_arima",
        model_kind=metis_pb2.ModelKind.ARIMA,
        inline_arrow_ipc=ipc,
        model_name="arima_m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    )
    res = stub.Fit(req)
    assert res.model_name == "arima_m"
    assert res.model_kind == metis_pb2.ModelKind.ARIMA
    assert "(1,1,1)" in res.chosen_order
    assert res.aic != 0.0
    assert res.input_rows == 48


def test_fit_arima_from_workspace_df(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    ipc = _make_arima_ipc()
    _import_df(stub, "s_arima_ws", "series", ipc)

    req = metis_pb2.FitRequest(
        session_id="s_arima_ws",
        model_kind=metis_pb2.ModelKind.ARIMA,
        input_df="series",
        model_name="arima_ws_m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    )
    res = stub.Fit(req)
    assert res.model_name == "arima_ws_m"
    assert res.input_rows == 48


def test_fit_arima_bad_order_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.FitRequest(
        session_id="s_badorder",
        model_kind=metis_pb2.ModelKind.ARIMA,
        inline_arrow_ipc=_make_arima_ipc(),
        model_name="bad_m",
        arima=metis_pb2.ArimaParams(order="not_an_order"),
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.Fit(req)
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


# ---------------------------------------------------------------------------
# Diagnose
# ---------------------------------------------------------------------------

def test_diagnose_after_linear_fit(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_diag",
        model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=_make_linear_ipc(),
        model_name="linear_diag",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    ))

    res = stub.Diagnose(metis_pb2.DiagnoseRequest(
        session_id="s_diag",
        model_name="linear_diag",
    ))
    assert len(res.checks) >= 2
    check_names = {c.name for c in res.checks}
    assert "ljung_box" in check_names
    assert "adf" in check_names


def test_diagnose_after_arima_fit(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_diag_arima",
        model_kind=metis_pb2.ModelKind.ARIMA,
        inline_arrow_ipc=_make_arima_ipc(),
        model_name="arima_diag",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    ))

    res = stub.Diagnose(metis_pb2.DiagnoseRequest(
        session_id="s_diag_arima",
        model_name="arima_diag",
    ))
    assert len(res.checks) >= 2


def test_diagnose_unknown_model_raises_not_found(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    with pytest.raises(grpc.RpcError) as exc:
        stub.Diagnose(metis_pb2.DiagnoseRequest(
            session_id="any_session",
            model_name="nonexistent_model",
        ))
    assert exc.value.code() == grpc.StatusCode.NOT_FOUND


def test_diagnose_missing_session_id_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    with pytest.raises(grpc.RpcError) as exc:
        stub.Diagnose(metis_pb2.DiagnoseRequest(session_id="", model_name="m"))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_diagnose_missing_model_name_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    with pytest.raises(grpc.RpcError) as exc:
        stub.Diagnose(metis_pb2.DiagnoseRequest(session_id="s", model_name=""))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


# ---------------------------------------------------------------------------
# Project
# ---------------------------------------------------------------------------

def test_project_arima(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    # Import + Fit
    ipc = _make_arima_ipc()
    _import_df(stub, "s_proj", "series", ipc)
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_proj",
        model_kind=metis_pb2.ModelKind.ARIMA,
        input_df="series",
        model_name="arima_m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    ))

    # Project
    res = stub.Project(metis_pb2.ProjectRequest(
        session_id="s_proj",
        model_name="arima_m",
        horizon="+6",
        confidence_level=0.90,
        output_df="forecast",
    ))
    assert res.output_df == "forecast"
    assert res.rows == 6
    assert res.schema_fingerprint  # non-empty


def test_project_exports_correct_schema(grpc_server_and_stub):
    """Export the forecast DF and verify it has the standard forecast columns."""
    _, stub = grpc_server_and_stub
    ipc = _make_arima_ipc()
    _import_df(stub, "s_proj_schema", "series", ipc)
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_proj_schema",
        model_kind=metis_pb2.ModelKind.ARIMA,
        input_df="series",
        model_name="arima_m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    ))
    stub.Project(metis_pb2.ProjectRequest(
        session_id="s_proj_schema",
        model_name="arima_m",
        horizon="+4",
        output_df="forecast",
    ))

    # Export and check schema
    chunks = list(stub.ExportDataFrame(metis_pb2.ExportRequest(
        session_id="s_proj_schema",
        df_name="forecast",
    )))
    assert len(chunks) > 0
    table = read_ipc_bytes(chunks[0].ipc_payload)
    assert set(table.schema.names) >= {"ds", "yhat", "yhat_lower", "yhat_upper", "kind"}
    assert table.num_rows == 4


def test_project_unknown_model_raises_not_found(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    with pytest.raises(grpc.RpcError) as exc:
        stub.Project(metis_pb2.ProjectRequest(
            session_id="s_nomodel",
            model_name="not_there",
            horizon="+3",
            output_df="out",
        ))
    assert exc.value.code() == grpc.StatusCode.NOT_FOUND


def test_project_bad_horizon_format_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    # Fit a model first
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_badhorizon",
        model_kind=metis_pb2.ModelKind.ARIMA,
        inline_arrow_ipc=_make_arima_ipc(),
        model_name="arima_m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    ))
    with pytest.raises(grpc.RpcError) as exc:
        stub.Project(metis_pb2.ProjectRequest(
            session_id="s_badhorizon",
            model_name="arima_m",
            horizon="12",   # missing '+' prefix
            output_df="out",
        ))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_project_linear_raises_unimplemented(grpc_server_and_stub):
    """LINEAR models do not support Project in Phase 2."""
    _, stub = grpc_server_and_stub
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_linear_proj",
        model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=_make_linear_ipc(),
        model_name="linear_m",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    ))
    with pytest.raises(grpc.RpcError) as exc:
        stub.Project(metis_pb2.ProjectRequest(
            session_id="s_linear_proj",
            model_name="linear_m",
            horizon="+3",
            output_df="out",
        ))
    assert exc.value.code() == grpc.StatusCode.UNIMPLEMENTED


# ---------------------------------------------------------------------------
# SimulateScenario
# ---------------------------------------------------------------------------

def _setup_arima_and_project(stub, session_id: str, horizon: int) -> None:
    ipc = _make_arima_ipc()
    _import_df(stub, session_id, "series", ipc)
    stub.Fit(metis_pb2.FitRequest(
        session_id=session_id,
        model_kind=metis_pb2.ModelKind.ARIMA,
        input_df="series",
        model_name="arima_m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    ))
    stub.Project(metis_pb2.ProjectRequest(
        session_id=session_id,
        model_name="arima_m",
        horizon=f"+{horizon}",
        output_df="forecast",
    ))


def test_simulate_scenario_scale(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    _setup_arima_and_project(stub, "s_sim", horizon=4)

    res = stub.SimulateScenario(metis_pb2.SimulateScenarioRequest(
        session_id="s_sim",
        forecast_df="forecast",
        deltas_json='{"scaleFactor": 1.1}',
        output_df="scenario",
    ))
    assert res.output_df == "scenario"
    assert res.rows == 4


def test_simulate_scenario_delta(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    _setup_arima_and_project(stub, "s_sim_delta", horizon=3)

    res = stub.SimulateScenario(metis_pb2.SimulateScenarioRequest(
        session_id="s_sim_delta",
        forecast_df="forecast",
        deltas_json='{"yhatDelta": 10.0}',
        output_df="scenario",
    ))
    assert res.rows == 3

    # Verify the delta was applied by exporting both frames and comparing
    forecast_chunks = list(stub.ExportDataFrame(metis_pb2.ExportRequest(
        session_id="s_sim_delta", df_name="forecast"
    )))
    scenario_chunks = list(stub.ExportDataFrame(metis_pb2.ExportRequest(
        session_id="s_sim_delta", df_name="scenario"
    )))
    f_table = read_ipc_bytes(forecast_chunks[0].ipc_payload)
    s_table = read_ipc_bytes(scenario_chunks[0].ipc_payload)

    f_yhat = f_table.column("yhat").to_pylist()
    s_yhat = s_table.column("yhat").to_pylist()
    for fv, sv in zip(f_yhat, s_yhat):
        assert abs(sv - (fv + 10.0)) < 1e-9, f"Expected {fv + 10.0}, got {sv}"


def test_simulate_scenario_kind_column(grpc_server_and_stub):
    """Scenario output must have kind='scenario' for all rows."""
    _, stub = grpc_server_and_stub
    _setup_arima_and_project(stub, "s_sim_kind", horizon=3)
    stub.SimulateScenario(metis_pb2.SimulateScenarioRequest(
        session_id="s_sim_kind",
        forecast_df="forecast",
        deltas_json="{}",
        output_df="scenario",
    ))
    chunks = list(stub.ExportDataFrame(metis_pb2.ExportRequest(
        session_id="s_sim_kind", df_name="scenario"
    )))
    table = read_ipc_bytes(chunks[0].ipc_payload)
    kinds = table.column("kind").to_pylist()
    assert all(k == "scenario" for k in kinds), f"Unexpected kind values: {kinds}"


def test_simulate_scenario_unknown_forecast_df_raises_not_found(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    with pytest.raises(grpc.RpcError) as exc:
        stub.SimulateScenario(metis_pb2.SimulateScenarioRequest(
            session_id="s_noforecast",
            forecast_df="nonexistent",
            deltas_json="{}",
            output_df="out",
        ))
    assert exc.value.code() == grpc.StatusCode.NOT_FOUND


def test_simulate_scenario_invalid_json_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    _setup_arima_and_project(stub, "s_sim_badjson", horizon=2)
    with pytest.raises(grpc.RpcError) as exc:
        stub.SimulateScenario(metis_pb2.SimulateScenarioRequest(
            session_id="s_sim_badjson",
            forecast_df="forecast",
            deltas_json="not-json",
            output_df="out",
        ))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


# ---------------------------------------------------------------------------
# Review-fix coverage: seed echo, warnings, ISO horizon, FAILED_PRECONDITION,
# input-rows cap, Prophet confidence_level.
# ---------------------------------------------------------------------------

from concurrent import futures  # noqa: E402

from metis import metis_pb2_grpc  # noqa: E402
from metis.config import MetisConfig  # noqa: E402
from metis.grpc_service import MetisServiceImpl  # noqa: E402
from metis.workspace import Workspace  # noqa: E402
from org.tatrman.kantheon.common.v1 import response_message_pb2  # noqa: E402


def _server_with_config(config: MetisConfig):
    """Build an in-process server/stub pair with an injected config."""
    ws = Workspace(
        idle_ttl_s=60, max_dfs_per_session=10,
        max_models_per_session=5, max_bytes_total=100 * 1024 * 1024,
    )
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    metis_pb2_grpc.add_MetisServiceServicer_to_server(MetisServiceImpl(ws, config), server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    return server, metis_pb2_grpc.MetisServiceStub(channel)


def _make_arima_ds_ipc(n: int = 48) -> bytes:
    """Monthly series with an explicit 'ds' date column (for ISO horizons)."""
    import pandas as pd
    rng = np.random.default_rng(42)
    ds = pd.date_range("2020-01-01", periods=n, freq="MS").astype(str).tolist()
    vals = [100.0 + i * 2 + rng.normal(0, 5) for i in range(n)]
    table = pa.table({"ds": pa.array(ds), "y": pa.array(vals, type=pa.float64())})
    return write_ipc_bytes(table)


def test_fit_echoes_seed_in_messages(grpc_server_and_stub):
    """Rule 6: every FitResult echoes the seed as an INFO message."""
    _, stub = grpc_server_and_stub
    res = stub.Fit(metis_pb2.FitRequest(
        session_id="s_seed", model_kind=metis_pb2.ModelKind.LINEAR,
        inline_arrow_ipc=_make_linear_ipc(), model_name="m",
        linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
    ))
    seed_msgs = [m for m in res.messages if m.code == "seed"]
    assert len(seed_msgs) == 1
    assert seed_msgs[0].severity == response_message_pb2.Severity.INFO
    assert "42" in seed_msgs[0].human_message


def test_fit_arima_auto_detect_fallback_emits_warning(grpc_server_and_stub):
    """A series too short for any seasonal candidate surfaces a WARNING on the wire."""
    _, stub = grpc_server_and_stub
    # 6 points: shorter than 2× the smallest seasonal candidate (4) → every
    # seasonality is skipped, so auto-detect falls back to non-seasonal.
    vals = [10.0, 12.0, 11.0, 14.0, 13.0, 16.0]
    table = pa.table({"y": pa.array(vals, type=pa.float64())})
    res = stub.Fit(metis_pb2.FitRequest(
        session_id="s_warn", model_kind=metis_pb2.ModelKind.ARIMA,
        inline_arrow_ipc=write_ipc_bytes(table), model_name="m",
        arima=metis_pb2.ArimaParams(),  # no explicit order → auto-detect
    ))
    warn_msgs = [m for m in res.messages
                 if m.severity == response_message_pb2.Severity.WARNING]
    assert any("fell back" in m.human_message for m in warn_msgs)


def test_project_iso_date_horizon(grpc_server_and_stub):
    """An ISO-date horizon resolves to the right number of monthly steps."""
    _, stub = grpc_server_and_stub
    stub.ImportDataFrame(iter([metis_pb2.ArrowChunk(
        header=metis_pb2.ImportHeader(session_id="s_iso", df_name="series"),
        ipc_payload=_make_arima_ds_ipc(),
    )]))
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_iso", model_kind=metis_pb2.ModelKind.ARIMA,
        input_df="series", model_name="m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    ))
    # Series ends 2023-12-01 (48 months from 2020-01). Target 2024-06-01 → 6 steps.
    res = stub.Project(metis_pb2.ProjectRequest(
        session_id="s_iso", model_name="m", horizon="2024-06-01", output_df="fc",
    ))
    assert res.rows == 6


def test_project_iso_date_before_series_end_fails_precondition(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    stub.ImportDataFrame(iter([metis_pb2.ArrowChunk(
        header=metis_pb2.ImportHeader(session_id="s_iso_pre", df_name="series"),
        ipc_payload=_make_arima_ds_ipc(),
    )]))
    stub.Fit(metis_pb2.FitRequest(
        session_id="s_iso_pre", model_kind=metis_pb2.ModelKind.ARIMA,
        input_df="series", model_name="m",
        arima=metis_pb2.ArimaParams(order="(1,1,1)(0,0,0,0)"),
    ))
    with pytest.raises(grpc.RpcError) as exc:
        stub.Project(metis_pb2.ProjectRequest(
            session_id="s_iso_pre", model_name="m",
            horizon="2020-06-01", output_df="fc",  # before series end
        ))
    assert exc.value.code() == grpc.StatusCode.FAILED_PRECONDITION


def test_simulate_non_forecast_frame_fails_precondition(grpc_server_and_stub):
    """A stored frame lacking forecast columns → FAILED_PRECONDITION (not INVALID_ARGUMENT)."""
    _, stub = grpc_server_and_stub
    # Import a plain (non-forecast) frame.
    plain = pa.table({"a": pa.array([1.0, 2.0]), "b": pa.array([3.0, 4.0])})
    stub.ImportDataFrame(iter([metis_pb2.ArrowChunk(
        header=metis_pb2.ImportHeader(session_id="s_nf", df_name="plain"),
        ipc_payload=write_ipc_bytes(plain),
    )]))
    with pytest.raises(grpc.RpcError) as exc:
        stub.SimulateScenario(metis_pb2.SimulateScenarioRequest(
            session_id="s_nf", forecast_df="plain",
            deltas_json='{"scaleFactor": 1.1}', output_df="out",
        ))
    assert exc.value.code() == grpc.StatusCode.FAILED_PRECONDITION


def test_fit_input_rows_cap_raises_resource_exhausted():
    """A fit input above METIS_MAX_FIT_ROWS is rejected before modelling."""
    server, stub = _server_with_config(MetisConfig(max_fit_rows=10))
    try:
        with pytest.raises(grpc.RpcError) as exc:
            stub.Fit(metis_pb2.FitRequest(
                session_id="s_cap", model_kind=metis_pb2.ModelKind.LINEAR,
                inline_arrow_ipc=_make_linear_ipc(n=50),  # 50 > cap 10
                model_name="m",
                linear=metis_pb2.LinearParams(x_cols=["x"], y_col="y"),
            ))
        assert exc.value.code() == grpc.StatusCode.RESOURCE_EXHAUSTED
    finally:
        server.stop(grace=None)


def test_run_with_timeout_raises_on_overrun():
    """Unit: the fit-timeout wrapper raises TimeoutError when fn overruns (deterministic)."""
    import time as _time
    from metis.models.fit_runner import run_with_timeout

    with pytest.raises(TimeoutError):
        run_with_timeout(lambda: _time.sleep(1.0), timeout_ms=20, label="sleeper")


def test_fit_timeout_maps_to_deadline_exceeded():
    """A fit that exceeds its per-kind deadline maps to DEADLINE_EXCEEDED.

    ARIMA auto-detect runs a multi-fit grid search — far more than a 1 ms budget
    allows — so the deadline reliably trips (unlike a sub-ms OLS).
    """
    server, stub = _server_with_config(MetisConfig(fit_timeout_ms_arima=1))
    try:
        rng = np.random.default_rng(7)
        vals = [100.0 + i + rng.normal(0, 3) for i in range(160)]
        ipc = write_ipc_bytes(pa.table({"y": pa.array(vals, type=pa.float64())}))
        with pytest.raises(grpc.RpcError) as exc:
            stub.Fit(metis_pb2.FitRequest(
                session_id="s_to", model_kind=metis_pb2.ModelKind.ARIMA,
                inline_arrow_ipc=ipc, model_name="m",
                arima=metis_pb2.ArimaParams(),  # auto-detect → slow grid search
            ))
        assert exc.value.code() == grpc.StatusCode.DEADLINE_EXCEEDED
    finally:
        server.stop(grace=None)
