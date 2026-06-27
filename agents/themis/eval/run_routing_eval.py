#!/usr/bin/env python3
"""
Themis routing eval harness — Phase 3 Stage 3.5.

Sibling to ``run_eval.py`` (resolution-quality corpus). This harness drives the
*routing* corpus (``eval/corpus/routing-seed.jsonl``): each line is a user
question plus the routing outcome Themis should produce. It POSTs every question
to the REST ``/v1/resolve`` endpoint with ``profile = CHAT_QUICK`` (the MCP tool
surface disables routing — see Main.kt), parses the proto ``ResolveResponse``,
and scores three things per question:

  - intent_kind        (Resolution.intent_kind, Stage 3.2)
  - chosen_agent_id    (RoutingDecision.chosen_agent_id, Stage 3.3)
  - routing layer hit  (RoutingDecision.layer_hit, 0..3)

It emits a JSONL of per-question results and a Markdown report (aggregate +
per-bucket + failed-question table), then enforces the CI thresholds in
``thresholds.yaml`` (exit non-zero on breach).

Buckets are read from the ``#``-comment headers in the corpus (JSONL has no
native comments); each data line is attributed to the most recent header.

Wire shape (response) is parsed tolerantly: the REST server emits the raw proto
``ResolveResponse`` as JSON. We accept both proto field names (snake_case) and
lowerCamelCase, and ``chosen_agent_id`` either as a bare string or as the nested
``AgentId`` shape ``{"value": "..."}``.

Live usage (against a deployed Themis):
    python run_routing_eval.py \
        --host localhost --port 7901 \
        --corpus eval/corpus/routing-seed.jsonl \
        --thresholds eval/thresholds.yaml \
        --output eval/results/routing-YYYYMMDD.jsonl \
        --report eval/results/routing-YYYYMMDD.md \
        --verbose

Self-test (no cluster, no LLM — runs in PR CI):
    python run_routing_eval.py --self-test

The self-test stands a local stdlib HTTP server that replays canned
``ResolveResponse`` JSON, then runs the full load → call → score → gate pipeline
against it and asserts the comparison + aggregation + threshold logic. This is
the Python-native equivalent of the Wiremock-replay the original Stage 3.5 task
doc called for — kept in CI; the live corpus run is relocated to the nightly
``themis-routing`` integration context (see eval/README.md).
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path

# Layer-3 "needs user pick" cases carry a null expected agent; this sentinel
# distinguishes "no agent expected" from "field absent".
LAYER3_NO_AGENT = None


@dataclass
class EvalExpected:
    intent_kind: str | None = None
    chosen_agent_id: str | None = None
    alternates_present: list[str] = field(default_factory=list)
    routing_layer_expected: int | None = None


@dataclass
class ActualRouting:
    intent_kind: str | None
    chosen_agent_id: str | None
    layer_hit: int | None
    needs_user_pick: bool
    alternates: list[str]
    outcome: str  # "resolved" | "awaiting" | "refusal" | "error"


@dataclass
class RoutingMatch:
    intent_kind_match: bool | None
    chosen_agent_match: bool | None
    layer_hit_match: bool | None
    alternates_match: bool | None

    def routing_ok(self) -> bool:
        """A question routes correctly iff the agent choice matches (or, for a
        Layer-3 needs_user_pick case, the expected alternates are all present)."""
        if self.chosen_agent_match is None:
            return bool(self.alternates_match)
        return bool(self.chosen_agent_match)


@dataclass
class EvalResult:
    qid: str
    bucket: str
    question: str
    expected: dict
    actual: dict
    match: dict


# --------------------------------------------------------------------------- #
# Corpus loading (with bucket tracking)
# --------------------------------------------------------------------------- #
def load_corpus(path: Path) -> list[tuple[str, dict]]:
    """Return (bucket, entry) pairs. ``#``-comment lines set the running bucket."""
    pairs: list[tuple[str, dict]] = []
    bucket = "(unbucketed)"
    with path.open() as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            if line.startswith("#"):
                # A bucket header is a comment that isn't pure boilerplate. We
                # treat any "#"-line that looks like a section title as a bucket;
                # the file's leading doc-comment block is harmless (no data lines
                # follow before the first real header).
                bucket = line.lstrip("#").strip()
                continue
            pairs.append((bucket, json.loads(line)))
    return pairs


# --------------------------------------------------------------------------- #
# HTTP — REST /v1/resolve (routing lives here, not on the MCP tool surface)
# --------------------------------------------------------------------------- #
def call_themis_routing(
    host: str,
    port: int,
    question: str,
    lang: str,
    timeout: float = 30.0,
) -> dict | None:
    url = f"http://{host}:{port}/v1/resolve"
    payload = json.dumps(
        {
            "conversation_id": f"routing-eval-{int(time.time() * 1000)}",
            "fresh": {"text": question, "locale": lang},
            "profile": "CHAT_QUICK",
        }
    ).encode()
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode()[:200]}", file=sys.stderr)
        return None
    except Exception as e:  # noqa: BLE001 — harness must survive any single failure
        print(f"  Error: {e}", file=sys.stderr)
        return None


# --------------------------------------------------------------------------- #
# Response parsing (proto-field-name tolerant)
# --------------------------------------------------------------------------- #
def _g(d: dict, *names: str):
    """First present key among proto snake_case / lowerCamelCase spellings."""
    for n in names:
        if isinstance(d, dict) and n in d:
            return d[n]
    return None


def _agent_id(v) -> str | None:
    """AgentId is ``{"value": "pythia"}`` in proto-JSON; tolerate a bare string."""
    if v is None:
        return None
    if isinstance(v, str):
        return v or None
    if isinstance(v, dict):
        inner = _g(v, "value", "id")
        return inner or None
    return None


def extract_routing(response: dict | None) -> ActualRouting:
    if response is None:
        return ActualRouting(None, None, None, False, [], "error")

    resolution = _g(response, "resolution") or {}
    awaiting = _g(response, "awaiting")
    refusal = _g(response, "refusal")

    if refusal:
        return ActualRouting(None, None, None, False, [], "refusal")

    intent_kind = _g(resolution, "intent_kind", "intentKind")
    routing = _g(resolution, "routing") or {}

    chosen = _agent_id(_g(routing, "chosen_agent_id", "chosenAgentId"))
    layer_hit = _g(routing, "layer_hit", "layerHit")
    needs_pick = bool(_g(routing, "needs_user_pick", "needsUserPick") or False)
    alts_raw = _g(routing, "alternates") or []
    alternates = [
        a
        for a in (_agent_id(_g(alt, "agent_id", "agentId")) for alt in alts_raw)
        if a
    ]

    outcome = "awaiting" if awaiting else "resolved"
    return ActualRouting(
        intent_kind=intent_kind,
        chosen_agent_id=chosen,
        layer_hit=layer_hit,
        needs_user_pick=needs_pick,
        alternates=alternates,
        outcome=outcome,
    )


def compare(expected: EvalExpected, actual: ActualRouting) -> RoutingMatch:
    intent_match = (
        None
        if expected.intent_kind is None
        else expected.intent_kind == actual.intent_kind
    )

    if expected.chosen_agent_id is LAYER3_NO_AGENT:
        # Layer-3 needs_user_pick case: no single agent expected; score alternates.
        chosen_match = None
        if expected.alternates_present:
            alt_match = all(a in actual.alternates for a in expected.alternates_present)
        else:
            alt_match = actual.needs_user_pick
    else:
        chosen_match = expected.chosen_agent_id == actual.chosen_agent_id
        alt_match = (
            None
            if not expected.alternates_present
            else all(a in actual.alternates for a in expected.alternates_present)
        )

    layer_match = (
        None
        if expected.routing_layer_expected is None
        else expected.routing_layer_expected == actual.layer_hit
    )

    return RoutingMatch(intent_match, chosen_match, layer_match, alt_match)


# --------------------------------------------------------------------------- #
# Aggregation
# --------------------------------------------------------------------------- #
@dataclass
class LayerHitStats:
    total: int = 0
    layer0: int = 0
    layer1: int = 0
    layer2: int = 0
    layer3: int = 0
    unknown: int = 0

    def record(self, layer: int | None) -> None:
        self.total += 1
        if layer == 0:
            self.layer0 += 1
        elif layer == 1:
            self.layer1 += 1
        elif layer == 2:
            self.layer2 += 1
        elif layer == 3:
            self.layer3 += 1
        else:
            self.unknown += 1

    def layer1_hit_rate(self) -> float:
        return self.layer1 / self.total if self.total else 0.0


@dataclass
class Aggregate:
    questions: int = 0
    errors: int = 0
    routing_correct: int = 0
    intent_total: int = 0
    intent_correct: int = 0
    # Layer-1 hit-rate is measured on *non-ambiguous* questions only (the ones
    # whose expected layer is not 3); a Layer-3 case correctly NOT hitting
    # Layer 1 must not drag the denominator.
    non_ambiguous: int = 0
    non_ambiguous_layer1: int = 0
    layer_hits: LayerHitStats = field(default_factory=LayerHitStats)

    def routing_accuracy(self) -> float:
        scored = self.questions - self.errors
        return self.routing_correct / scored if scored else 0.0

    def intent_accuracy(self) -> float:
        return self.intent_correct / self.intent_total if self.intent_total else 0.0

    def layer1_hit_rate(self) -> float:
        return (
            self.non_ambiguous_layer1 / self.non_ambiguous
            if self.non_ambiguous
            else 0.0
        )


def evaluate(
    pairs: list[tuple[str, dict]],
    call_fn,
    verbose: bool = False,
) -> tuple[Aggregate, dict[str, dict], list[EvalResult]]:
    agg = Aggregate()
    buckets: dict[str, dict] = {}
    results: list[EvalResult] = []

    for idx, (bucket, entry) in enumerate(pairs):
        qid = entry.get("id", f"q{idx}")
        question = entry.get("question") or entry.get("text", "")
        lang = entry.get("lang", "cs")
        exp_raw = entry.get("expected", {})
        expected = EvalExpected(
            intent_kind=exp_raw.get("intent_kind"),
            chosen_agent_id=exp_raw.get("chosen_agent_id", LAYER3_NO_AGENT),
            alternates_present=exp_raw.get("alternates_present", []) or [],
            routing_layer_expected=exp_raw.get("routing_layer_expected"),
        )

        b = buckets.setdefault(bucket, {"total": 0, "routing_correct": 0})
        b["total"] += 1
        agg.questions += 1

        response = call_fn(question, lang)
        actual = extract_routing(response)
        match = compare(expected, actual)

        if actual.outcome == "error":
            agg.errors += 1
            if verbose:
                print(f"  [{qid}] ERROR — no response | {question[:60]}")
            results.append(
                EvalResult(qid, bucket, question, asdict(expected), asdict(actual), asdict(match))
            )
            continue

        agg.layer_hits.record(actual.layer_hit)

        if match.routing_ok():
            agg.routing_correct += 1
            b["routing_correct"] += 1

        if expected.intent_kind is not None:
            agg.intent_total += 1
            if match.intent_kind_match:
                agg.intent_correct += 1

        if expected.routing_layer_expected is not None and expected.routing_layer_expected != 3:
            agg.non_ambiguous += 1
            if actual.layer_hit == 1:
                agg.non_ambiguous_layer1 += 1

        if verbose:
            status = "OK" if match.routing_ok() else "FAIL"
            print(
                f"  [{qid}] {status} | intent {expected.intent_kind}->{actual.intent_kind} "
                f"agent {expected.chosen_agent_id}->{actual.chosen_agent_id} "
                f"L{expected.routing_layer_expected}->{actual.layer_hit} | {question[:50]}"
            )

        results.append(
            EvalResult(qid, bucket, question, asdict(expected), asdict(actual), asdict(match))
        )

    return agg, buckets, results


# --------------------------------------------------------------------------- #
# Thresholds (minimal flat-YAML reader — keeps the harness stdlib-only)
# --------------------------------------------------------------------------- #
def load_thresholds(path: Path | None) -> dict[str, float]:
    """Read the three threshold floats from a flat ``thresholds.yaml``.

    Deliberately tiny: we parse only ``key: value`` lines under a ``thresholds:``
    block, ignoring comments. Avoids a PyYAML dependency for a 3-line config.
    """
    defaults = {
        "routing_accuracy": 0.0,
        "layer1_hit_rate": 0.0,
        "intent_kind_accuracy": 0.0,
    }
    if path is None or not path.exists():
        return defaults
    for raw in path.read_text().splitlines():
        line = raw.split("#", 1)[0].strip()
        if ":" not in line:
            continue
        key, _, val = line.partition(":")
        key = key.strip()
        val = val.strip()
        if key in defaults and val:
            try:
                defaults[key] = float(val)
            except ValueError:
                pass
    return defaults


# --------------------------------------------------------------------------- #
# Reporting
# --------------------------------------------------------------------------- #
def write_jsonl(results: list[EvalResult], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as f:
        for r in results:
            f.write(json.dumps(asdict(r), ensure_ascii=False) + "\n")
    print(f"JSONL results written to {path}")


def render_markdown(
    agg: Aggregate,
    buckets: dict[str, dict],
    results: list[EvalResult],
    thresholds: dict[str, float],
    date_label: str,
) -> str:
    lh = agg.layer_hits
    lines = [
        f"# Themis Routing Eval — {date_label}",
        "",
        "## Aggregate",
        "",
        f"- Questions: {agg.questions} ({agg.errors} errors)",
        f"- Routing accuracy: {agg.routing_correct} / {agg.questions - agg.errors} "
        f"({agg.routing_accuracy():.1%})  — gate ≥ {thresholds['routing_accuracy']:.0%}",
        f"- Intent-kind accuracy: {agg.intent_correct} / {agg.intent_total} "
        f"({agg.intent_accuracy():.1%})  — gate ≥ {thresholds['intent_kind_accuracy']:.0%}",
        f"- Layer-1 hit-rate (non-ambiguous): {agg.non_ambiguous_layer1} / {agg.non_ambiguous} "
        f"({agg.layer1_hit_rate():.1%})  — gate ≥ {thresholds['layer1_hit_rate']:.0%}",
        f"- Layer-hit distribution: L0={lh.layer0}  L1={lh.layer1}  L2={lh.layer2}  "
        f"L3={lh.layer3}  unknown={lh.unknown}",
        "",
        "## Per bucket",
        "",
        "| Bucket | Questions | Routing accuracy |",
        "|---|---|---|",
    ]
    for name, b in buckets.items():
        acc = b["routing_correct"] / b["total"] if b["total"] else 0.0
        lines.append(f"| {name} | {b['total']} | {b['routing_correct']}/{b['total']} ({acc:.0%}) |")

    failed = [r for r in results if not RoutingMatch(**r.match).routing_ok()]
    lines += ["", "## Per-question (failed only)", ""]
    if failed:
        lines += ["| # | Question | Expected | Actual | Diagnosis |", "|---|---|---|---|---|"]
        for r in failed:
            exp = r.expected
            act = r.actual
            diag = "no response" if act["outcome"] == "error" else "mismatch"
            lines.append(
                f"| {r.qid} | {r.question[:50]} | "
                f"{exp['chosen_agent_id']}/L{exp['routing_layer_expected']} | "
                f"{act['chosen_agent_id']}/L{act['layer_hit']} | {diag} |"
            )
    else:
        lines.append("_None — all questions routed as expected._")

    return "\n".join(lines) + "\n"


def gate(agg: Aggregate, thresholds: dict[str, float]) -> tuple[bool, list[str]]:
    failures = []
    if agg.routing_accuracy() < thresholds["routing_accuracy"]:
        failures.append(
            f"routing_accuracy {agg.routing_accuracy():.1%} < {thresholds['routing_accuracy']:.0%}"
        )
    if agg.non_ambiguous and agg.layer1_hit_rate() < thresholds["layer1_hit_rate"]:
        failures.append(
            f"layer1_hit_rate {agg.layer1_hit_rate():.1%} < {thresholds['layer1_hit_rate']:.0%}"
        )
    if agg.intent_total and agg.intent_accuracy() < thresholds["intent_kind_accuracy"]:
        failures.append(
            f"intent_kind_accuracy {agg.intent_accuracy():.1%} < {thresholds['intent_kind_accuracy']:.0%}"
        )
    return (len(failures) == 0, failures)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Themis routing eval harness (Stage 3.5)")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=7901)
    p.add_argument("--corpus", default="eval/corpus/routing-seed.jsonl")
    p.add_argument("--thresholds", default="eval/thresholds.yaml")
    p.add_argument("--output", help="JSONL results path")
    p.add_argument("--report", help="Markdown report path")
    p.add_argument("--date-label", default=time.strftime("%Y-%m-%d"))
    p.add_argument("--verbose", action="store_true")
    p.add_argument("--self-test", action="store_true", help="Run against a local fake Themis")
    return p.parse_args(argv)


def run(args: argparse.Namespace) -> int:
    corpus_path = Path(args.corpus)
    if not corpus_path.exists():
        print(f"Corpus not found: {corpus_path}", file=sys.stderr)
        return 1
    pairs = load_corpus(corpus_path)
    if not pairs:
        print("Corpus is empty (only headers?).", file=sys.stderr)
        return 1

    thresholds = load_thresholds(Path(args.thresholds) if args.thresholds else None)
    print(f"Routing eval: {len(pairs)} questions → http://{args.host}:{args.port}/v1/resolve\n")

    def call_fn(question: str, lang: str) -> dict | None:
        return call_themis_routing(args.host, args.port, question, lang)

    agg, buckets, results = evaluate(pairs, call_fn, verbose=args.verbose)

    report = render_markdown(agg, buckets, results, thresholds, args.date_label)
    print(report)

    if args.output:
        write_jsonl(results, Path(args.output))
    if args.report:
        rp = Path(args.report)
        rp.parent.mkdir(parents=True, exist_ok=True)
        rp.write_text(report)
        print(f"Markdown report written to {rp}")

    if agg.errors == agg.questions:
        print("All requests failed — is themis-mcp running and reachable?", file=sys.stderr)
        return 1

    passed, failures = gate(agg, thresholds)
    if passed:
        print("PASS — all routing thresholds met.")
        return 0
    print(f"FAIL — {'; '.join(failures)}")
    return 1


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        from selftest import main as selftest_main  # local module, CI-only

        sys.exit(selftest_main())
    sys.exit(run(parse_args()))
