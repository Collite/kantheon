"""Stage 1.2 T1: workspace unit tests."""
import time
import pytest
import pyarrow as pa

from metis.workspace import Workspace, NotFoundError, AlreadyExistsError, ResourceExhaustedError
from metis.arrow_io import write_ipc_bytes


@pytest.fixture()
def ws():
    return Workspace(
        idle_ttl_s=5,
        max_dfs_per_session=3,
        max_models_per_session=2,
        max_bytes_total=10 * 1024 * 1024,
    )


def _make_arrow_bytes(n: int = 10) -> bytes:
    table = pa.table({"x": list(range(n)), "y": [float(i) for i in range(n)]})
    return write_ipc_bytes(table)


def test_put_and_get_df(ws):
    data = _make_arrow_bytes()
    ws.put_df("s1", "df1", data)
    assert ws.get_df("s1", "df1") == data


def test_df_session_isolation(ws):
    data1 = _make_arrow_bytes(5)
    data2 = _make_arrow_bytes(10)
    ws.put_df("s1", "df1", data1)
    ws.put_df("s2", "df1", data2)
    assert ws.get_df("s1", "df1") == data1
    assert ws.get_df("s2", "df1") == data2


def test_get_df_not_found(ws):
    with pytest.raises(NotFoundError):
        ws.get_df("no_session", "no_df")


def test_put_df_already_exists(ws):
    ws.put_df("s1", "df1", _make_arrow_bytes())
    with pytest.raises(AlreadyExistsError):
        ws.put_df("s1", "df1", _make_arrow_bytes())


def test_df_cap_per_session(ws):
    for i in range(3):
        ws.put_df("s1", f"df{i}", _make_arrow_bytes())
    with pytest.raises(ResourceExhaustedError):
        ws.put_df("s1", "df_overflow", _make_arrow_bytes())


def test_put_and_get_model(ws):
    model_obj = {"type": "linear", "coef": [1.0, 2.0]}
    ws.put_model("s1", "m1", model_obj)
    assert ws.get_model("s1", "m1") == model_obj


def test_model_cap_per_session(ws):
    ws.put_model("s1", "m1", {})
    ws.put_model("s1", "m2", {})
    with pytest.raises(ResourceExhaustedError):
        ws.put_model("s1", "m3", {})


def test_drop_df(ws):
    ws.put_df("s1", "df1", _make_arrow_bytes())
    assert ws.drop("s1", "df1") is True
    with pytest.raises(NotFoundError):
        ws.get_df("s1", "df1")


def test_drop_nonexistent_returns_false(ws):
    assert ws.drop("s1", "no_such") is False


def test_drop_model(ws):
    ws.put_model("s1", "m1", {"x": 1})
    assert ws.drop("s1", "m1") is True
    with pytest.raises(NotFoundError):
        ws.get_model("s1", "m1")


def test_status_reflects_counts(ws):
    ws.put_df("s1", "df1", _make_arrow_bytes())
    ws.put_df("s2", "df1", _make_arrow_bytes())
    ws.put_model("s1", "m1", {})
    status = ws.status()
    assert status.dataframes == 2
    assert status.models == 1
    assert status.sessions == 2
    assert status.workspace_bytes > 0


def test_ttl_eviction(ws):
    ws_short = Workspace(
        idle_ttl_s=1,
        max_dfs_per_session=10,
        max_models_per_session=10,
        max_bytes_total=10 * 1024 * 1024,
    )
    ws_short.put_df("s1", "df1", _make_arrow_bytes())
    time.sleep(2)
    ws_short._evict_expired()
    with pytest.raises(NotFoundError):
        ws_short.get_df("s1", "df1")


def test_concurrent_session_isolation(ws):
    ws.put_df("sa", "data", _make_arrow_bytes(3))
    ws.put_df("sb", "data", _make_arrow_bytes(7))
    assert ws.get_df("sa", "data") != ws.get_df("sb", "data")
