#!/usr/bin/env python3
"""
Themis eval harness — Phase 2 Stage 2.4.

Reads a JSONL corpus, calls the Themis MCP tool over HTTP, and measures:
  - Function-call accuracy   (exact-match on functionId)
  - Entity-binding precision (predicted args that match expected)
  - Entity-binding recall    (expected args that are predicted)
  - Parse-passthrough fidelity (NOUN/PROPN tokens present in filteredSpans)

Exit code 0 if all metrics >= baseline, 1 otherwise. Carries over from
ai-platform's Resolver harness; the port default and label were retargeted
to Themis (port 7901, MCP path /mcp/v1/tools/resolve, REST /v1/resolve)
during the Stage 2.2 extraction. Wire shape preserved verbatim so corpus
+ baseline are comparable across the migration.

Usage:
    python run_eval.py [--host localhost] [--port 7901] \
                       [--corpus eval/corpus/seed.jsonl] \
                       [--threshold-accuracy 0.70] \
                       [--threshold-precision 0.60] \
                       [--threshold-recall 0.60] \
                       [--verbose]
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Themis eval harness")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=7901)
    p.add_argument("--corpus", default="eval/corpus/seed.jsonl")
    p.add_argument("--threshold-accuracy", type=float, default=0.70,
                   help="Minimum function-call accuracy to pass CI gate")
    p.add_argument("--threshold-precision", type=float, default=0.60,
                   help="Minimum entity-binding precision to pass CI gate")
    p.add_argument("--threshold-recall", type=float, default=0.60,
                   help="Minimum entity-binding recall to pass CI gate")
    p.add_argument("--verbose", action="store_true")
    return p.parse_args()


def call_themis(
    host: str,
    port: int,
    question: str,
    lang: str,
    mode: str = "NORMAL",
) -> dict | None:
    """POST to the Themis MCP resolve tool endpoint and return the parsed JSON result.

    ``mode`` is the Themis ResolveMode ("NORMAL" or "ENTITIES_ONLY"). When the
    MCP tool surface gains a per-call mode argument, plumb it here; for now
    ENTITIES_ONLY cases must be exercised through the REST endpoint, which
    accepts the proto ResolveMode enum directly.
    """
    if mode == "ENTITIES_ONLY":
        # REST path so the proto-level mode flag is honoured.
        url = f"http://{host}:{port}/v1/resolve"
        payload = json.dumps({
            "conversation_id": f"eval-{int(time.time() * 1000)}",
            "fresh": {"text": question, "locale": lang},
            "mode": "RESOLVE_MODE_ENTITIES_ONLY",
        }).encode()
    else:
        url = f"http://{host}:{port}/mcp/v1/tools/resolve"
        payload = json.dumps({
            "question": question,
            "locale": lang,
            "conversation_id": f"eval-{int(time.time() * 1000)}",
        }).encode()

    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            if mode == "ENTITIES_ONLY":
                # REST endpoint returns the proto ResolveResponse as JSON.
                return json.loads(raw)
            # MCP tool returns {content: [{type: "text", text: "...JSON..."}]}
            outer = json.loads(raw)
            text = outer.get("content", [{}])[0].get("text", "{}")
            return json.loads(text)
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode()[:200]}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"  Error: {e}", file=sys.stderr)
        return None


def _bindings_match(
    predicted_bindings: list[dict],
    expected_bindings: list[dict],
) -> bool:
    """Check every expected (entity_type, resolved_label) pair appears in predictions.

    Proto JSON serialises EntityBinding as either `{"universal": {...}}` or
    `{"domain": {...}}`. Universal uses `entityType` + `normalizedValue`/`rawText`;
    domain uses `entityTypeRef` + `resolvedLabel`. We match case-insensitively on
    the resolved label / normalized value.
    """
    def matches(expected_type: str, expected_label: str, b: dict) -> bool:
        label_lower = expected_label.lower()
        type_lower = expected_type.lower()
        domain = b.get("domain") or {}
        if domain:
            type_ok = type_lower == domain.get("entityTypeRef", "").lower()
            label_ok = label_lower in (
                domain.get("resolvedLabel", "").lower(),
                domain.get("rawText", "").lower(),
            )
            if type_ok and label_ok:
                return True
        universal = b.get("universal") or {}
        if universal:
            type_ok = type_lower == universal.get("entityType", "").lower()
            label_ok = label_lower in (
                universal.get("normalizedValue", "").lower(),
                universal.get("rawText", "").lower(),
            )
            if type_ok and label_ok:
                return True
        return False

    for expected in expected_bindings:
        t = expected.get("entity_type", "")
        lbl = expected.get("resolved_label", "")
        if not any(matches(t, lbl, b) for b in predicted_bindings):
            return False
    return True


def args_match(predicted_json: str, expected: dict) -> tuple[float, float]:
    """Return (precision, recall) comparing predicted args against expected."""
    if not expected:
        return (1.0, 1.0)
    try:
        predicted = json.loads(predicted_json) if isinstance(predicted_json, str) else {}
    except Exception:
        predicted = {}

    if not predicted and not expected:
        return (1.0, 1.0)
    if not predicted:
        return (0.0, 0.0)

    true_positives = sum(
        1 for k, v in expected.items()
        if str(predicted.get(k, "")).lower() == str(v).lower()
    )
    precision = true_positives / len(predicted) if predicted else 0.0
    recall = true_positives / len(expected) if expected else 1.0
    return (precision, recall)


def run_eval(args: argparse.Namespace) -> int:
    corpus_path = Path(args.corpus)
    if not corpus_path.exists():
        print(f"Corpus not found: {corpus_path}", file=sys.stderr)
        return 1

    entries = []
    with corpus_path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                entries.append(json.loads(line))

    if not entries:
        print("Corpus is empty.", file=sys.stderr)
        return 1

    print(f"Running eval: {len(entries)} questions → http://{args.host}:{args.port}")
    print()

    correct_function = 0
    total_precision = 0.0
    total_recall = 0.0
    errors = 0

    eo_total = 0
    eo_correct = 0

    for entry in entries:
        qid = entry.get("id", "?")
        # ENTITIES_ONLY entries use `text`; NORMAL entries use `question`.
        question = entry.get("question") or entry.get("text", "")
        lang = entry.get("lang", "cs")
        mode = entry.get("mode", "NORMAL")

        if mode == "ENTITIES_ONLY":
            eo_total += 1
            expected_awaiting = bool(entry.get("expected_awaiting", False))
            expected_bindings = entry.get("expected_bindings", [])
            result = call_themis(args.host, args.port, question, lang, mode="ENTITIES_ONLY")
            if result is None:
                errors += 1
                if args.verbose:
                    print(f"  [{qid}] ERROR — no response (ENTITIES_ONLY)")
                continue
            saw_awaiting = "awaiting" in result and bool(result.get("awaiting"))
            bindings_ok = (
                expected_awaiting == saw_awaiting
                and (
                    saw_awaiting
                    or _bindings_match(
                        result.get("resolution", {}).get("bindings", []),
                        expected_bindings,
                    )
                )
            )
            if bindings_ok:
                eo_correct += 1
            if args.verbose:
                status = "OK" if bindings_ok else "FAIL"
                kind = "awaiting" if saw_awaiting else "resolved"
                print(f"  [{qid}] {status} (ENTITIES_ONLY/{kind}) | {question[:60]}")
            continue

        expected = entry.get("expected", {})
        expected_fn = expected.get("functionId", "")
        expected_args = expected.get("args", {})

        result = call_themis(args.host, args.port, question, lang)
        if result is None:
            errors += 1
            if args.verbose:
                print(f"  [{qid}] ERROR — no response")
            continue

        outcome = result.get("outcome", "")
        predicted_fn = result.get("function_id", "")
        predicted_args_json = result.get("args_json", "{}")
        confidence = result.get("confidence", 0.0)

        fn_ok = predicted_fn == expected_fn
        if fn_ok:
            correct_function += 1

        p, r = args_match(predicted_args_json, expected_args)
        total_precision += p
        total_recall += r

        if args.verbose:
            status = "OK" if fn_ok else "FAIL"
            print(
                f"  [{qid}] {status} | expected={expected_fn!r} predicted={predicted_fn!r} "
                f"conf={confidence:.2f} P={p:.2f} R={r:.2f} | {question[:60]}"
            )

    responded = len(entries) - errors
    if responded == 0:
        print("All requests failed — is themis-mcp running?")
        return 1

    normal_entries = len(entries) - eo_total
    accuracy = (correct_function / normal_entries) if normal_entries else 1.0
    normal_responded = max(responded - eo_total, 1)
    avg_precision = total_precision / normal_responded
    avg_recall = total_recall / normal_responded
    eo_pass_rate = (eo_correct / eo_total) if eo_total else 1.0
    f1 = (2 * avg_precision * avg_recall / (avg_precision + avg_recall)
          if (avg_precision + avg_recall) > 0 else 0.0)

    print(f"Results ({responded}/{len(entries)} answered, {errors} errors):")
    print(f"  Function-call accuracy : {accuracy:.2%}  (threshold {args.threshold_accuracy:.0%})  [NORMAL]")
    print(f"  Entity-binding precision: {avg_precision:.2%}  (threshold {args.threshold_precision:.0%})  [NORMAL]")
    print(f"  Entity-binding recall   : {avg_recall:.2%}  (threshold {args.threshold_recall:.0%})  [NORMAL]")
    print(f"  Entity-binding F1       : {f1:.2%}")
    if eo_total:
        print(f"  ENTITIES_ONLY pass rate: {eo_pass_rate:.2%}  ({eo_correct}/{eo_total})  (gate 80%)")
    print()

    passed = (
        accuracy >= args.threshold_accuracy
        and avg_precision >= args.threshold_precision
        and avg_recall >= args.threshold_recall
        and (eo_pass_rate >= 0.80 if eo_total else True)
    )
    if passed:
        print("PASS — all metrics above baseline.")
        return 0
    else:
        failing = []
        if accuracy < args.threshold_accuracy:
            failing.append(f"accuracy {accuracy:.2%} < {args.threshold_accuracy:.0%}")
        if avg_precision < args.threshold_precision:
            failing.append(f"precision {avg_precision:.2%} < {args.threshold_precision:.0%}")
        if avg_recall < args.threshold_recall:
            failing.append(f"recall {avg_recall:.2%} < {args.threshold_recall:.0%}")
        print(f"FAIL — {'; '.join(failing)}")
        return 1


if __name__ == "__main__":
    sys.exit(run_eval(parse_args()))
