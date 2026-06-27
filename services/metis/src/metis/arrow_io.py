"""
Arrow IPC utilities for Metis.

schema_fingerprint: SHA-256 over the **canonical logical-schema string** — the
  platform-wide cross-engine schema identity (Charon `Integrity` / Steropes).
  NOT raw IPC bytes: review-006 R3 (2026-06-15, Bora) established that hashing
  Arrow IPC schema-message bytes is not stable across Arrow implementations
  (Arrow Java vs pyarrow emit different flatbuffer bytes for the same logical
  schema, and pyarrow is not even self-consistent across a re-serialise). The
  digest is therefore derived from the logical schema, byte-identically to the
  Kotlin `Integrity.canonicalSchemaString` and the Python reference at
  `services/charon/src/test/resources/fixtures/integrity/regenerate.py`.

read_ipc_bytes: Arrow IPC -> pa.Table
write_ipc_bytes: pa.Table -> Arrow IPC bytes (one batch per chunk_rows rows)
iter_ipc_chunks: yield IPC bytes, chunk_rows rows at a time
"""
from __future__ import annotations

import hashlib
import io
from collections.abc import Iterator

import pyarrow as pa
import pyarrow.ipc as pa_ipc


# ---------------------------------------------------------------------------
# Canonical schema fingerprint (cross-engine; mirrors Charon Integrity.kt)
#
# Canonical form (charon/contracts.md §6):
#   - top-level fields joined by '\n', declaration order;
#   - field := name '|' type '|' nullability ['<' child ';' child … '>'];
#   - nullability := 'null' (nullable) | 'nonnull';
#   - type tokens spell out every parameter, using SHARED unit tokens
#     ('s|ms|us|ns', date 'day|ms', interval 'ym|dt|mdn');
#   - field/schema metadata is excluded (provenance, not identity);
#   - SHA-256 of the UTF-8 bytes, lowercase hex.
# ---------------------------------------------------------------------------

def _encode_type(t: pa.DataType) -> str:
    if pa.types.is_null(t):
        return "null"
    if pa.types.is_boolean(t):
        return "bool"
    if pa.types.is_integer(t):
        return "int" + str(t.bit_width) + ("s" if pa.types.is_signed_integer(t) else "u")
    if pa.types.is_floating(t):
        return "float" + {16: "16", 32: "32", 64: "64"}[t.bit_width]
    if pa.types.is_decimal(t):
        bit_width = 256 if pa.types.is_decimal256(t) else 128
        return f"decimal{bit_width}_{t.precision}_{t.scale}"
    if pa.types.is_string(t):
        return "utf8"
    if pa.types.is_large_string(t):
        return "large_utf8"
    if pa.types.is_fixed_size_binary(t):
        return "fixed_size_binary_" + str(t.byte_width)
    if pa.types.is_binary(t):
        return "binary"
    if pa.types.is_large_binary(t):
        return "large_binary"
    if pa.types.is_date32(t):
        return "date_day"
    if pa.types.is_date64(t):
        return "date_ms"
    if pa.types.is_time(t):
        bit_width = 32 if pa.types.is_time32(t) else 64
        return f"time_{t.unit}_{bit_width}"
    if pa.types.is_timestamp(t):
        return "timestamp_" + t.unit + "_" + (t.tz if t.tz is not None else "")
    if pa.types.is_duration(t):
        return "duration_" + t.unit
    # map before the list family: MapType subclasses ListType, so an `is_list`
    # check first would (on some pyarrow versions) swallow a map and mis-encode
    # it as "list". Must mirror the shared reference (generate.py) exactly.
    if pa.types.is_map(t):
        return "map_" + ("sorted" if t.keys_sorted else "unsorted")
    if pa.types.is_list(t):
        return "list"
    if pa.types.is_large_list(t):
        return "large_list"
    if pa.types.is_fixed_size_list(t):
        return "fixed_size_list_" + str(t.list_size)
    if pa.types.is_struct(t):
        return "struct"
    raise ValueError(f"Metis fingerprint: unsupported Arrow type {t}")


def _type_children(t: pa.DataType) -> list[pa.Field]:
    if pa.types.is_struct(t):
        return [t.field(i) for i in range(t.num_fields)]
    # map before list (MapType subclasses ListType): the entries-wrapped {key,
    # value} struct — the form Arrow Java exposes via Field.children, so Kotlin
    # (Charon/Brontes) and Python (Metis/Steropes) agree on the same canonical
    # bytes. pyarrow flattens it to key_field/item_field; synthesize it back.
    if pa.types.is_map(t):
        return [pa.field("entries", pa.struct([t.key_field, t.item_field]), nullable=False)]
    if pa.types.is_list(t) or pa.types.is_large_list(t) or pa.types.is_fixed_size_list(t):
        return [t.value_field]
    return []


def _encode_field(f: pa.Field) -> str:
    nullability = "null" if f.nullable else "nonnull"
    kids = _type_children(f.type)
    child_part = "" if not kids else "<" + ";".join(_encode_field(c) for c in kids) + ">"
    return f.name + "|" + _encode_type(f.type) + "|" + nullability + child_part


def canonical_schema_string(schema: pa.Schema) -> str:
    """Implementation-independent string form of *schema* (debugging / cross-check)."""
    return "\n".join(_encode_field(f) for f in schema)


def schema_fingerprint(schema: pa.Schema) -> str:
    """Return SHA-256 (lowercase hex) of the canonical logical-schema string.

    Cross-engine identity: byte-identical to Charon `Integrity.fingerprint` and
    the Steropes worker. A schema Metis stages must hash equal to what Charon /
    a Polars worker computes for the same logical schema — see the module
    docstring and `tests/test_fingerprint_cross_engine.py`.
    """
    return hashlib.sha256(canonical_schema_string(schema).encode("utf-8")).hexdigest()


def read_ipc_bytes(data: bytes) -> pa.Table:
    """Deserialise Arrow IPC stream bytes to a pa.Table."""
    buf = io.BytesIO(data)
    reader = pa_ipc.open_stream(buf)
    return reader.read_all()


def write_ipc_bytes(table: pa.Table, chunk_rows: int = 65536) -> bytes:
    """Serialise a pa.Table to Arrow IPC stream bytes.

    Records are written in batches of chunk_rows. The result is a single
    contiguous IPC stream (suitable for workspace storage or inline transport).
    """
    buf = io.BytesIO()
    with pa_ipc.new_stream(buf, table.schema) as writer:
        for batch in table.to_batches(max_chunksize=chunk_rows):
            writer.write_batch(batch)
    return buf.getvalue()


def iter_ipc_chunks(table: pa.Table, chunk_rows: int = 65536) -> Iterator[bytes]:
    """Yield IPC bytes for each chunk of chunk_rows rows.

    Each yielded bytes value is a self-contained IPC stream (can be read
    independently). Used for gRPC streaming (ExportDataFrame).
    """
    for batch in table.to_batches(max_chunksize=chunk_rows):
        buf = io.BytesIO()
        with pa_ipc.new_stream(buf, table.schema) as writer:
            writer.write_batch(batch)
        yield buf.getvalue()
