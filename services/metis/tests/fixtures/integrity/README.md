# Cross-engine schema-fingerprint fixtures

These two files are **copied verbatim** from the canonical source of truth at
`services/charon/src/test/resources/fixtures/integrity/` (review-006 R3,
2026-06-15). They pin the platform-wide schema fingerprint so Metis's Python
`schema_fingerprint` can be asserted byte-identical to Charon (Kotlin) and the
Steropes worker (Python):

- `python-canonical-fingerprint.hex` — SHA-256 (lowercase hex) of the reference schema.
- `python-canonical-schema.txt` — the canonical schema string the digest is taken over.

`tests/test_fingerprint_cross_engine.py` rebuilds the reference schema and asserts
both match. **Do not edit by hand.** If the canonical algorithm ever changes,
regenerate at the source (`charon/.../integrity/regenerate.py`) and re-copy.
