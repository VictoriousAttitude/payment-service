"""History model for the chaos run.

Jepsen's shape: a workload generator issues operations against a system under
fault injection, every attempt is recorded, and a PURE checker later decides
whether the recorded history plus the final observed state is consistent with
the promised model. This module is that recorded vocabulary. It has no I/O and
no dependencies so the checker built on it stays deterministic and auditable.

The load-bearing idea is the outcome trichotomy. A distributed op that we sent
does not have a boolean result. It has three:

  OK   the server acknowledged it (2xx). The op DEFINITELY took effect, and the
       system now owes us durability: killing the primary must not lose it.
  FAIL the server definitively rejected it (a clean 4xx). The op DEFINITELY did
       not take effect, so it must leave no trace.
  INFO unknown. A timeout, a dropped connection mid-nemesis, or a 5xx. The op
       MAY or MAY NOT have applied. The checker must not assume either way, but
       whatever it did must be atomic (no half-applied posting group).

Conflating INFO with FAIL is the classic lost-update bug: a write that actually
committed gets treated as if it never happened. Keeping INFO distinct is the
whole point of recording a history instead of just asserting on final state.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum


class OpKind(Enum):
    CREATE = "CREATE"
    CAPTURE = "CAPTURE"
    REFUND = "REFUND"
    VOID = "VOID"


class Outcome(Enum):
    OK = "OK"
    FAIL = "FAIL"
    INFO = "INFO"


@dataclass(frozen=True, slots=True)
class HistoryEntry:
    """One completed operation attempt.

    `idempotency_key` is what makes the retry story checkable: the workload
    reissues the SAME key on an INFO outcome (the only safe thing a caller can
    do when it does not know if the write landed), so a correct server must
    collapse those retries onto one transaction.
    """

    index: int
    kind: OpKind
    idempotency_key: str
    outcome: Outcome
    http_status: int | None = None
    # CREATE returns the server-assigned id, but only on an OK outcome.
    returned_id: str | None = None
    # CAPTURE/REFUND/VOID target an existing transaction.
    target_id: str | None = None
    amount: int | None = None
    currency: str | None = None

    def __post_init__(self) -> None:
        if self.outcome is Outcome.OK and self.http_status is None:
            raise ValueError(
                f"op {self.index}: OK outcome must carry the acknowledging status"
            )

    @classmethod
    def from_json(cls, raw: Mapping[str, object]) -> HistoryEntry:
        return cls(
            index=_as_int(raw, "index"),
            kind=OpKind(_as_str(raw, "kind")),
            idempotency_key=_as_str(raw, "idempotency_key"),
            outcome=Outcome(_as_str(raw, "outcome")),
            http_status=_opt_int(raw, "http_status"),
            returned_id=_opt_str(raw, "returned_id"),
            target_id=_opt_str(raw, "target_id"),
            amount=_opt_int(raw, "amount"),
            currency=_opt_str(raw, "currency"),
        )

    def to_json(self) -> dict[str, object]:
        return {
            "index": self.index,
            "kind": self.kind.value,
            "idempotency_key": self.idempotency_key,
            "outcome": self.outcome.value,
            "http_status": self.http_status,
            "returned_id": self.returned_id,
            "target_id": self.target_id,
            "amount": self.amount,
            "currency": self.currency,
        }


@dataclass(frozen=True, slots=True)
class FinalTransaction:
    """A transaction as observed by a GET once the cluster has quiesced."""

    id: str
    status: str
    amount: int
    captured_amount: int
    refunded_amount: int
    currency: str


@dataclass(frozen=True, slots=True)
class ReconciliationSnapshot:
    """Mirror of the JVM GET /api/v1/reconciliation report.

    We do not re-derive these checks in Python: the service already owns the
    ledger invariants (per-posting-group balance, global debits==credits,
    snapshot==SUM). The harness asserts that AFTER the faults, that self-report
    still comes back clean. The novel work the harness adds is P1/P2, which the
    service cannot check about itself because only the client knows what it
    acknowledged.
    """

    healthy: bool
    stuck_transactions: tuple[str, ...]
    transactions_without_ledger_entries: tuple[str, ...]
    unbalanced_posting_groups: tuple[str, ...]
    amount_mismatched_transactions: tuple[str, ...]
    global_balanced: bool
    snapshot_drift: tuple[str, ...]

    @classmethod
    def from_json(cls, raw: Mapping[str, object]) -> ReconciliationSnapshot:
        balance = raw.get("globalBalance")
        balanced = (
            bool(balance.get("balanced")) if isinstance(balance, Mapping) else False
        )
        return cls(
            healthy=bool(raw.get("healthy")),
            stuck_transactions=_str_tuple(raw, "stuckTransactions"),
            transactions_without_ledger_entries=_str_tuple(
                raw, "transactionsWithoutLedgerEntries"
            ),
            unbalanced_posting_groups=_str_tuple(raw, "unbalancedPostingGroups"),
            amount_mismatched_transactions=_str_tuple(
                raw, "amountMismatchedTransactions"
            ),
            global_balanced=balanced,
            snapshot_drift=_str_tuple(raw, "snapshotDrift"),
        )


@dataclass(frozen=True, slots=True)
class AnchorSnapshot:
    """Mirror of the JVM GET /api/v1/ledger-anchors/verify report."""

    verified_epochs: int
    healthy: bool
    unanchored_entries: int
    failures: tuple[str, ...]

    @classmethod
    def from_json(cls, raw: Mapping[str, object]) -> AnchorSnapshot:
        failures = raw.get("failures")
        rendered: tuple[str, ...] = ()
        if isinstance(failures, list):
            rendered = tuple(
                f"epoch {f.get('epoch')} {f.get('reason')}: "
                f"expected={f.get('expected')} actual={f.get('actual')}"
                for f in failures
                if isinstance(f, Mapping)
            )
        return cls(
            verified_epochs=coerce_int(raw.get("verifiedEpochs")),
            healthy=bool(raw.get("healthy")),
            unanchored_entries=coerce_int(raw.get("unanchoredEntries")),
            failures=rendered,
        )


@dataclass(frozen=True, slots=True)
class FinalState:
    """Everything the checker needs about the system after the run."""

    transactions: Mapping[str, FinalTransaction]
    reconciliation: ReconciliationSnapshot
    anchor: AnchorSnapshot


def _as_int(raw: Mapping[str, object], key: str) -> int:
    value = raw[key]
    if not isinstance(value, int):
        raise ValueError(f"field {key!r} must be an int, got {value!r}")
    return value


def _as_str(raw: Mapping[str, object], key: str) -> str:
    value = raw[key]
    if not isinstance(value, str):
        raise ValueError(f"field {key!r} must be a str, got {value!r}")
    return value


def coerce_int(value: object) -> int:
    """Read a JSON number as int, defaulting to 0 for absent/non-numeric.

    bool is a subclass of int, so exclude it explicitly to avoid True -> 1.
    """
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


def _opt_int(raw: Mapping[str, object], key: str) -> int | None:
    value = raw.get(key)
    return value if isinstance(value, int) else None


def _opt_str(raw: Mapping[str, object], key: str) -> str | None:
    value = raw.get(key)
    return value if isinstance(value, str) else None


def _str_tuple(raw: Mapping[str, object], key: str) -> tuple[str, ...]:
    value = raw.get(key)
    if not isinstance(value, list):
        return ()
    return tuple(str(item) for item in value)
