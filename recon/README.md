# recon - external-oracle reconciliation

The JVM payment service already proves its ledger is *internally* consistent:

1. append-only ledger (DB triggers reject UPDATE/DELETE),
2. per-currency balance invariant (debits == credits within each currency),
3. a scheduled reconciliation sweep (stuck-PENDING, amount invariants).

All three answer "does the ledger agree with itself?". **None can detect that
the ledger disagrees with the money that actually moved at the processor.** That
is the canonical payments failure: a capture booked but never settled, or a fee
the acquirer took that we never modelled. This engine is that missing layer - an
**external oracle**. It reconciles our ledger export against the processor's
settlement file (Stripe-style), the same way a payments team runs three-way
reconciliation (ledger <-> processor <-> bank).

## Design

**Functional core, imperative shell.** `domain/` is pure (no I/O, no deps):
`Money`, the domain rows, and `reconcile()`. `adapters/` does the dirty work -
CSV parsing, Prometheus output. The CLI wires them. The core is deterministic
and exhaustively property-tested.

**Completeness is provable, not best-effort.** `reconcile()` joins the two sides
on `reference` and partitions every reference into exactly one of
`{matched, ledger-only, settlement-only}`. The partition is exhaustive by set
algebra, so nothing is silently dropped - "did we check everything?" has a yes/no
answer. A reference duplicated within one side is *ambiguous*, so it is excluded
from matching and raised as `DUPLICATE_REFERENCE` rather than silently deduped (a
silent dedup would let a double-booking pass unnoticed).

**Money is exact.** Integer minor units in the core, `Decimal` only at the parse
boundary, converted with the currency's ISO 4217 exponent (EUR 2, JPY 0, BHD 3).
No float ever. Equality includes the currency, so a cross-currency comparison is
`False`, never a silent unit error; cross-currency arithmetic raises.

**Fee check is a real oracle, not N-version.** We compare the fee *we expected*
against the fee the processor *actually took* (margin-leak detection). We do not
re-derive our own fee in Python as a cross-check: by Knight & Leveson (IEEE TSE
1986) independent implementations of the same spec fail *correlatedly*, so a
self-N-version check only catches transcription bugs a single unit test already
catches - it cannot catch a wrong fee *policy*. The settlement file is the only
input that is genuinely independent of our code, so it is the only fee that is
worth comparing against.

**Settlement lag is modelled.** A ledger movement absent from the file is not
automatically a discrepancy - processors settle on T+1/T+2. Movements newer than
`--window-days` are reported as `pending`; only older ones become
`MISSING_IN_SETTLEMENT`. A settlement line with no ledger counterpart is always
`MISSING_IN_LEDGER` (unbooked money - the highest-severity class).

**Zero runtime dependencies.** The core can't drift with a third-party release
and is trivially auditable. Dev-only: pytest, hypothesis, ruff, mypy.

## Run

```sh
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]" pytest hypothesis        # or: pip install pytest hypothesis
recon --ledger samples/ledger.csv --settlement samples/settlement.csv \
      --as-of 2026-06-23 --window-days 2 --metrics-file recon.prom
echo "exit: $?"   # non-zero when discrepancies are found
```

Exit code is non-zero on any discrepancy, so a cron/CI step fails loudly.
Metrics are written in Prometheus text-exposition format for the node_exporter
textfile collector (batch jobs publish through a file, not an HTTP endpoint).

## Test

```sh
pytest -q          # unit + hypothesis property tests
ruff check . && mypy
```
