#!/usr/bin/env python3
"""
Self-test for the Themis routing eval harness (Stage 3.5, task T1 acceptance).

Stands a local stdlib HTTP server that replays canned proto-shaped
``ResolveResponse`` JSON keyed by question, then drives the full harness pipeline
(load corpus → POST /v1/resolve → parse → compare → aggregate → gate) over real
HTTP against it and asserts the RoutingMatch + aggregate + threshold-gate logic.

This is the CI-runnable, dependency-free stand-in for the Wiremock replay the
original task doc called for. The live corpus run is relocated to the nightly
``themis-routing`` integration context (see README.md). Run via:

    python run_routing_eval.py --self-test
    # or directly:
    python selftest.py
"""

from __future__ import annotations

import json
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# Allow `python selftest.py` from anywhere: the harness lives next to us.
sys.path.insert(0, str(Path(__file__).resolve().parent))

import run_routing_eval as h  # noqa: E402


def _resolution(intent_kind, agent, layer, *, confidence=0.9):
    """A proto-shaped (snake_case) ResolveResponse with a RoutingDecision."""
    return {
        "resolution": {
            "function_id": "listUnpaidInvoices",
            "confidence": confidence,
            "intent_kind": intent_kind,
            "routing": {
                "chosen_agent_id": {"value": agent},
                "layer_hit": layer,
                "confidence": confidence,
                "needs_user_pick": False,
                "alternates": [],
            },
        }
    }


def _needs_pick(intent_kind, alternates):
    return {
        "resolution": {
            "intent_kind": intent_kind,
            "routing": {
                "chosen_agent_id": {"value": ""},
                "layer_hit": 3,
                "needs_user_pick": True,
                "alternates": [
                    {"agent_id": {"value": a}, "score": 0.4, "why": "candidate"}
                    for a in alternates
                ],
            },
        }
    }


# question text → canned response. Mirrors the 6-bucket corpus below.
CANNED: dict[str, dict] = {
    "Q_PROCEDURAL_ERP": _resolution("PROCEDURAL", "golem-erp", 1),
    "Q_PROCEDURAL_XDOMAIN": _resolution("PROCEDURAL", "pythia", 1),
    "Q_RCA": _resolution("RCA", "pythia", 1),
    # Deliberately wrong agent — exercises a routing FAIL row.
    "Q_FORECAST_WRONG": _resolution("FORECAST", "golem-erp", 1),
    # camelCase response — exercises field-name tolerance.
    "Q_SIMULATION_CAMEL": {
        "resolution": {
            "intentKind": "SIMULATION",
            "routing": {
                "chosenAgentId": {"value": "pythia"},
                "layerHit": 2,
                "needsUserPick": False,
                "alternates": [],
            },
        }
    },
    "Q_AMBIGUOUS": _needs_pick("PROCEDURAL", ["pythia", "golem-erp"]),
}

# Corpus mirrors CANNED; bucket headers must be tracked by the loader.
CORPUS = """# PROCEDURAL — single Golem-ERP domain
{"id":"q1","question":"Q_PROCEDURAL_ERP","lang":"cs","expected":{"intent_kind":"PROCEDURAL","chosen_agent_id":"golem-erp","routing_layer_expected":1}}
# PROCEDURAL — cross-domain
{"id":"q2","question":"Q_PROCEDURAL_XDOMAIN","lang":"en","expected":{"intent_kind":"PROCEDURAL","chosen_agent_id":"pythia","routing_layer_expected":1}}
# RCA
{"id":"q3","question":"Q_RCA","lang":"cs","expected":{"intent_kind":"RCA","chosen_agent_id":"pythia","routing_layer_expected":1}}
# FORECAST
{"id":"q4","question":"Q_FORECAST_WRONG","lang":"cs","expected":{"intent_kind":"FORECAST","chosen_agent_id":"pythia","routing_layer_expected":1}}
# SIMULATION
{"id":"q5","question":"Q_SIMULATION_CAMEL","lang":"cs","expected":{"intent_kind":"SIMULATION","chosen_agent_id":"pythia","routing_layer_expected":2}}
# Ambiguous
{"id":"q6","question":"Q_AMBIGUOUS","lang":"cs","expected":{"intent_kind":"PROCEDURAL","chosen_agent_id":null,"alternates_present":["pythia","golem-erp"],"routing_layer_expected":3}}
"""


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silence per-request logging
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode())
        question = body.get("fresh", {}).get("text", "")
        canned = CANNED.get(question)
        if canned is None:
            self.send_response(404)
            self.end_headers()
            return
        payload = json.dumps(canned).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def _assert(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def main() -> int:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory() as tmp:
            corpus = Path(tmp) / "routing-seed.jsonl"
            corpus.write_text(CORPUS)
            pairs = h.load_corpus(corpus)

            # Loader: 6 data lines, 6 distinct buckets.
            _assert(len(pairs) == 6, f"expected 6 pairs, got {len(pairs)}")
            buckets_seen = {b for b, _ in pairs}
            _assert(len(buckets_seen) == 6, f"expected 6 buckets, got {buckets_seen}")

            def call_fn(question: str, lang: str):
                return h.call_themis_routing("127.0.0.1", port, question, lang)

            agg, buckets, results = h.evaluate(pairs, call_fn, verbose=False)

            # 5 of 6 route correctly (q4 forecast → wrong agent).
            _assert(agg.routing_correct == 5, f"routing_correct={agg.routing_correct}")
            _assert(agg.errors == 0, f"errors={agg.errors}")
            _assert(abs(agg.routing_accuracy() - 5 / 6) < 1e-9, "routing_accuracy")

            # Intent-kind: all 6 expected intents match the canned responses.
            _assert(agg.intent_total == 6 and agg.intent_correct == 6, "intent acc")

            # Layer-1 hit-rate denominator excludes the ambiguous (L3) case → 5.
            #   q1,q2,q3 hit L1; q4 hits L1 (wrong agent but right layer); q5 hits L2.
            _assert(agg.non_ambiguous == 5, f"non_ambiguous={agg.non_ambiguous}")
            _assert(agg.non_ambiguous_layer1 == 4, f"L1={agg.non_ambiguous_layer1}")
            _assert(abs(agg.layer1_hit_rate() - 4 / 5) < 1e-9, "layer1_hit_rate")

            # Layer-hit distribution.
            _assert(agg.layer_hits.layer1 == 4, "layer1 count")
            _assert(agg.layer_hits.layer2 == 1, "layer2 count")
            _assert(agg.layer_hits.layer3 == 1, "layer3 count")

            # camelCase response (q5) parsed correctly.
            q5 = next(r for r in results if r.qid == "q5")
            _assert(q5.actual["chosen_agent_id"] == "pythia", "camelCase agent parse")
            _assert(q5.match["chosen_agent_match"] is True, "camelCase match")

            # Layer-3 ambiguous (q6): no single agent, alternates present → routing_ok.
            q6 = next(r for r in results if r.qid == "q6")
            _assert(q6.match["chosen_agent_match"] is None, "L3 chosen None")
            _assert(q6.match["alternates_match"] is True, "L3 alternates")

            # Gate: a low threshold passes; a high one fails.
            ok, _ = h.gate(agg, {"routing_accuracy": 0.7, "layer1_hit_rate": 0.6, "intent_kind_accuracy": 0.85})
            _assert(ok, "gate should pass at conservative thresholds")
            bad, failures = h.gate(agg, {"routing_accuracy": 0.95, "layer1_hit_rate": 0.6, "intent_kind_accuracy": 0.85})
            _assert(not bad and failures, "gate should fail at 95% routing accuracy")

            # Markdown report renders and lists the one failed question (q4).
            md = h.render_markdown(
                agg, buckets, results,
                {"routing_accuracy": 0.7, "layer1_hit_rate": 0.6, "intent_kind_accuracy": 0.85},
                "self-test",
            )
            _assert("q4" in md, "failed question q4 in report")
            _assert("Per bucket" in md, "per-bucket section present")

        print("routing-eval self-test: PASS (all assertions green)")
        return 0
    except AssertionError as e:
        print(f"routing-eval self-test: FAIL — {e}", file=sys.stderr)
        return 1
    finally:
        server.shutdown()


if __name__ == "__main__":
    sys.exit(main())
