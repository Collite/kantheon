"""Cross-engine schema-fingerprint cross-check.

The schema fingerprint is the platform-wide cross-engine schema identity:
a schema Metis stages must hash *equal* to what Charon (Kotlin `Integrity`)
and the Polars worker compute for the same logical schema. review-006 R3
(2026-06-15) pinned the canonical algorithm and a reference digest; this test
asserts Metis's `schema_fingerprint` reproduces it byte-identically.

The reference schema mirrors `charon/.../integrity/regenerate.py` exactly —
the same fields, in the same order, so the canonical string and the SHA-256
must both match the pinned fixtures.
"""
from __future__ import annotations

import json
from pathlib import Path

import pyarrow as pa
import pyarrow.ipc as pa_ipc
import pytest

from metis.arrow_io import canonical_schema_string, schema_fingerprint

_FIXTURES = Path(__file__).parent / "fixtures" / "integrity"

# The platform-wide cross-engine fixture set (fork Stage 3.4 T2): the SAME
# *.arrow bytes Charon (Kotlin), Mssql, and Polars hash, with pinned digests
# in fingerprints.json. Metis must reproduce every one — including the list and
# map fixtures that exercise the nested-type child recursion (the entries-wrapped
# map is where pyarrow's flattened key/value would silently diverge from Arrow
# Java's Field.children). services/metis/tests → parents[3] == repo root.
_SHARED = Path(__file__).resolve().parents[3] / "shared" / "testdata" / "fingerprints"


def _shared_fixtures() -> list[tuple[str, str]]:
    expected = json.loads((_SHARED / "fingerprints.json").read_text())
    return sorted(expected.items())


# Byte-identical to REFERENCE_SCHEMA in charon's regenerate.py — exercises the
# contracts §5 types plus a nested struct.
REFERENCE_SCHEMA = pa.schema(
    [
        pa.field("name", pa.utf8(), nullable=False),
        pa.field("count", pa.int64(), nullable=False),
        pa.field("score", pa.float64(), nullable=True),
        pa.field("amount", pa.decimal128(38, 9), nullable=True),
        pa.field("ts", pa.timestamp("us", tz="UTC"), nullable=True),
        pa.field("d", pa.date32(), nullable=True),
        pa.field("active", pa.bool_(), nullable=False),
        pa.field("payload", pa.binary(), nullable=True),
        pa.field(
            "meta",
            pa.struct(
                [
                    pa.field("key", pa.utf8(), nullable=False),
                    pa.field("val", pa.int32(), nullable=True),
                ]
            ),
            nullable=True,
        ),
    ]
)


def _pinned_digest() -> str:
    return (_FIXTURES / "python-canonical-fingerprint.hex").read_text().strip()


def _pinned_canonical() -> str:
    return (_FIXTURES / "python-canonical-schema.txt").read_text().strip()


def test_canonical_string_matches_pinned_cross_engine_fixture():
    """The canonical string is byte-identical to Charon's pinned reference."""
    assert canonical_schema_string(REFERENCE_SCHEMA) == _pinned_canonical()


def test_fingerprint_matches_pinned_cross_engine_digest():
    """The SHA-256 digest equals the cross-engine pinned digest (Kotlin == Python)."""
    assert schema_fingerprint(REFERENCE_SCHEMA) == _pinned_digest()


def test_fingerprint_is_metadata_independent():
    """Field/schema metadata is provenance, not identity — must not change the digest."""
    with_meta = REFERENCE_SCHEMA.with_metadata({"origin": "charon", "run": "42"})
    assert schema_fingerprint(with_meta) == schema_fingerprint(REFERENCE_SCHEMA)


def test_fingerprint_is_nullability_sensitive():
    """Flipping a field's nullability changes the identity."""
    flipped = pa.schema(
        [pa.field("name", pa.utf8(), nullable=True)]
        + [REFERENCE_SCHEMA.field(i) for i in range(1, len(REFERENCE_SCHEMA.names))]
    )
    assert schema_fingerprint(flipped) != schema_fingerprint(REFERENCE_SCHEMA)


@pytest.mark.parametrize("fname,digest", _shared_fixtures())
def test_metis_agrees_with_shared_cross_engine_fixtures(fname: str, digest: str):
    """Metis hashes every shared *.arrow byte-identically to Charon/Mssql/Polars.

    Reads the canonical fixture bytes (not a Metis-local copy) and recomputes the
    digest with Metis's own algorithm — the "same algorithm, many implementations,
    must agree" CI pin. Covers list and map, which the struct-only REFERENCE_SCHEMA
    above does not: a flattened (non-entries-wrapped) map would pass every test in
    this file except this one.
    """
    with pa_ipc.open_stream((_SHARED / fname).read_bytes()) as reader:
        schema = reader.schema
    assert schema_fingerprint(schema) == digest, (
        f"{fname}: Metis digest diverges from the shared cross-engine pin "
        f"(canonical string: {canonical_schema_string(schema)!r})"
    )


def test_fingerprint_is_order_sensitive():
    """Column order is part of the identity."""
    reordered = pa.schema(
        [REFERENCE_SCHEMA.field(1), REFERENCE_SCHEMA.field(0)]
        + [REFERENCE_SCHEMA.field(i) for i in range(2, len(REFERENCE_SCHEMA.names))]
    )
    assert schema_fingerprint(reordered) != schema_fingerprint(REFERENCE_SCHEMA)
