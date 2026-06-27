"""Stage 1.2 T5: arrow_io — IPC round-trip + schema fingerprint."""
import pyarrow as pa

from metis.arrow_io import read_ipc_bytes, write_ipc_bytes, schema_fingerprint, iter_ipc_chunks


def _sample_table(n: int = 20) -> pa.Table:
    return pa.table({
        "ds": pa.array([f"2024-{i:02d}-01" for i in range(1, n + 1)]),
        "y": pa.array([float(i * 10) for i in range(n)]),
    })


def test_round_trip_byte_identical():
    t = _sample_table()
    encoded = write_ipc_bytes(t)
    recovered = read_ipc_bytes(encoded)
    assert t.equals(recovered)


def test_schema_fingerprint_stable():
    schema = pa.schema([("ds", pa.string()), ("y", pa.float64())])
    fp1 = schema_fingerprint(schema)
    fp2 = schema_fingerprint(schema)
    assert fp1 == fp2
    assert len(fp1) == 64  # SHA-256 hex


def test_schema_fingerprint_differs_on_type_change():
    s1 = pa.schema([("x", pa.int32())])
    s2 = pa.schema([("x", pa.int64())])
    assert schema_fingerprint(s1) != schema_fingerprint(s2)


def test_schema_fingerprint_differs_on_name_change():
    s1 = pa.schema([("x", pa.float64())])
    s2 = pa.schema([("y", pa.float64())])
    assert schema_fingerprint(s1) != schema_fingerprint(s2)


def test_round_trip_preserves_null_values():
    t = pa.table({"a": pa.array([1, None, 3], type=pa.int64())})
    assert read_ipc_bytes(write_ipc_bytes(t)).equals(t)


def test_write_chunked_reassembles():
    t = _sample_table(100)
    chunks = list(iter_ipc_chunks(t, chunk_rows=10))
    assert len(chunks) > 1
    # Each chunk is a valid IPC stream
    for chunk in chunks:
        assert len(chunk) > 0
        recovered = read_ipc_bytes(chunk)
        assert recovered.schema.equals(t.schema)
