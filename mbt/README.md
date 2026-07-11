# mbt — model-based conformance testing of the live payment API

Hypothesis stateful testing (`RuleBasedStateMachine`) drives the real HTTP API
and a pure Python reference model in lockstep and fails on any divergence.
Example-based tests check sequences someone thought of; this searches the
sequence space (interleaved partial captures/refunds, voids, idempotency-key
replays and conflicts) and shrinks any failure to a minimal reproducer.

## Layout

- `src/mbt/model.py` — pure reference model: the transition table mirrored
  from `PaymentStatus.canTransitionTo` plus capture/refund/void semantics in
  the server's decision order (amount bound 422 before transition 409).
  Zero dependencies, zero I/O; this is the oracle and the mutmut target.
- `src/mbt/client.py` — thin urllib client. Returns exact status codes and
  parsed bodies; raises on transport errors (conformance testing assumes a
  healthy server, unlike the chaos harness's ok/fail/info trichotomy).
- `tests/test_model.py` — mutation-hardened unit tests for the model.
- `tests/test_live_conformance.py` — the stateful machine. Skips itself when
  no server is listening, so the default `pytest` run stays green.

## Running

Against the compose stack (seeded dev merchant):

    docker compose up -d --build   # repo root
    cd mbt
    uv run pytest tests/test_live_conformance.py -q

Gates (no server needed):

    uv run ruff check . && uv run mypy && uv run pytest tests/test_model.py -q
    uv run mutmut run   # zero survivors on src/mbt/model.py

## Nondeterminism

The provider simulator authorizes ~90% of payments at random. The machine
treats the authorization outcome as an environmental input: after create it
polls until the payment stabilizes to AUTHORIZED or FAILED, records which,
and from there every subsequent behavior is deterministic and checked.
