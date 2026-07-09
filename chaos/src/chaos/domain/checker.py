"""The pure consistency checker.

Given the recorded history and the final observed state, decide whether the
run satisfies the model the payment service promises. No I/O, no clock, no
randomness: the same inputs always yield the same verdict, so a failing run is
reproducible from its recorded artifacts alone. This is the correctness-bearing
core the mutation gate mutates.

Seven properties. P1 and P2 are the ones the harness genuinely adds, because
they need the CLIENT'S view of what was acknowledged, which the server cannot
know about itself. P3..P7 assert the service's own post-fault self-report still
comes back clean, which is what turns "the reconciler says it is fine" into a
gated pass/fail correlated with a specific injected fault.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from enum import Enum

from chaos.domain.history import FinalState, HistoryEntry, OpKind, Outcome


class Property(Enum):
    P1_DURABILITY = "P1_acknowledged_write_durability"
    P2_IDEMPOTENCY = "P2_idempotent_no_duplication"
    P3_ATOMICITY = "P3_unknown_op_atomicity"
    P4_GLOBAL_BALANCE = "P4_global_double_entry_balance"
    P5_SNAPSHOT = "P5_snapshot_equals_ledger_sum"
    P6_ANCHOR = "P6_anchor_chain_integrity"
    P7_CONVERGENCE = "P7_no_stuck_ops_convergence"


@dataclass(frozen=True, slots=True)
class Violation:
    property: Property
    detail: str


@dataclass(frozen=True, slots=True)
class CheckReport:
    violations: tuple[Violation, ...]

    @property
    def ok(self) -> bool:
        return len(self.violations) == 0

    def by_property(self) -> dict[Property, int]:
        counts: dict[Property, int] = {}
        for violation in self.violations:
            counts[violation.property] = counts.get(violation.property, 0) + 1
        return counts


def check(history: Sequence[HistoryEntry], final: FinalState) -> CheckReport:
    violations: list[Violation] = []
    violations.extend(_durability(history, final))
    violations.extend(_idempotency(history))
    violations.extend(_atomicity(final))
    violations.extend(_global_balance(final))
    violations.extend(_snapshot(final))
    violations.extend(_anchor(final))
    violations.extend(_convergence(final))
    return CheckReport(tuple(violations))


def _durability(
    history: Sequence[HistoryEntry], final: FinalState
) -> list[Violation]:
    """P1: an acknowledged write must survive.

    Every CREATE the server answered with 2xx must be present in the final
    state. If a payment that returned 201 has vanished after a primary kill,
    quorum synchronous replication did NOT hold and a committed write was lost.
    This is the single property the whole HA Postgres design exists to make
    true, so it is the first thing the harness proves.
    """
    found: list[Violation] = []
    for entry in history:
        if entry.kind is not OpKind.CREATE or entry.outcome is not Outcome.OK:
            continue
        if entry.returned_id is None:
            found.append(
                Violation(
                    Property.P1_DURABILITY,
                    f"op {entry.index}: OK create carried no transaction id",
                )
            )
        elif entry.returned_id not in final.transactions:
            found.append(
                Violation(
                    Property.P1_DURABILITY,
                    f"op {entry.index}: acknowledged create {entry.returned_id} "
                    f"absent from final state (lost committed write)",
                )
            )
    return found


def _idempotency(history: Sequence[HistoryEntry]) -> list[Violation]:
    """P2: one idempotency key yields at most one transaction.

    The workload retries the SAME key after an INFO outcome (the only safe
    client behaviour under uncertainty). A correct server collapses those onto
    a single transaction. If one key acknowledged two distinct ids, the retry
    double-charged, which under fault injection is the most likely money bug.
    """
    ids_by_key: dict[str, set[str]] = {}
    for entry in history:
        if (
            entry.kind is OpKind.CREATE
            and entry.outcome is Outcome.OK
            and entry.returned_id is not None
        ):
            ids_by_key.setdefault(entry.idempotency_key, set()).add(entry.returned_id)

    found: list[Violation] = []
    for key in sorted(ids_by_key):
        ids = ids_by_key[key]
        if len(ids) > 1:
            found.append(
                Violation(
                    Property.P2_IDEMPOTENCY,
                    f"idempotency key {key} acknowledged {len(ids)} distinct "
                    f"transactions {sorted(ids)} (duplicate write)",
                )
            )
    return found


def _atomicity(final: FinalState) -> list[Violation]:
    """P3: an unknown-outcome op is all-or-nothing.

    An INFO capture/refund that was cut off mid-flight either fully posted or
    not at all. A half-applied posting group leaves that group's debits and
    credits unequal, which is exactly what unbalancedPostingGroups reports (and
    amountMismatched catches a capture recorded past its authorization).
    """
    found: list[Violation] = []
    for group in final.reconciliation.unbalanced_posting_groups:
        found.append(
            Violation(
                Property.P3_ATOMICITY,
                f"posting group {group} is unbalanced (partially applied op)",
            )
        )
    for txn in final.reconciliation.amount_mismatched_transactions:
        found.append(
            Violation(
                Property.P3_ATOMICITY,
                f"transaction {txn} has ledger totals inconsistent with its state",
            )
        )
    return found


def _global_balance(final: FinalState) -> list[Violation]:
    """P4: double-entry holds globally, per currency."""
    if not final.reconciliation.global_balanced:
        return [
            Violation(
                Property.P4_GLOBAL_BALANCE,
                "global ledger is not balanced (debits != credits in some currency)",
            )
        ]
    return []


def _snapshot(final: FinalState) -> list[Violation]:
    """P5: the O(1) balance snapshot still equals the naive ledger SUM.

    The snapshot is a derived cache with no append-only trigger. If a crash
    interrupted a fold, or a concurrent replica double-folded, drift shows up
    here. Any drift means a balance read could be wrong.
    """
    return [
        Violation(Property.P5_SNAPSHOT, f"snapshot drift: {line}")
        for line in final.reconciliation.snapshot_drift
    ]


def _anchor(final: FinalState) -> list[Violation]:
    """P6: the epoch Merkle anchor chain still verifies.

    A crash mid-anchor must not leave a broken chain link or a wrong root. An
    unhealthy report or any failure means the tamper-evidence chain no longer
    proves the ledger is the same ledger.
    """
    found: list[Violation] = []
    if not final.anchor.healthy:
        found.append(
            Violation(
                Property.P6_ANCHOR,
                "anchor verification reported unhealthy",
            )
        )
    for failure in final.anchor.failures:
        found.append(Violation(Property.P6_ANCHOR, f"anchor failure: {failure}"))
    return found


def _convergence(final: FinalState) -> list[Violation]:
    """P7: after quiescence, nothing is stuck.

    A payment left in PENDING because its provider dispatch died with a pod, or
    a transaction with no ledger entries, means a scheduled job (outbox,
    reconciliation) did not resume after the fault. Under ShedLock the surviving
    replica must pick the work up, so a persistent stuck row is a real failure.
    """
    found: list[Violation] = []
    for txn in final.reconciliation.stuck_transactions:
        found.append(
            Violation(
                Property.P7_CONVERGENCE,
                f"transaction {txn} is stuck past the threshold (job did not resume)",
            )
        )
    for txn in final.reconciliation.transactions_without_ledger_entries:
        found.append(
            Violation(
                Property.P7_CONVERGENCE,
                f"transaction {txn} has no ledger entries (posting never converged)",
            )
        )
    return found


def render(report: CheckReport) -> str:
    lines = [
        f"properties checked: {len(Property)}",
        f"violations:         {len(report.violations)}",
    ]
    for violation in report.violations:
        lines.append(f"  - {violation.property.value}: {violation.detail}")
    lines.append("CONSISTENT" if report.ok else "INCONSISTENT")
    return "\n".join(lines)
