from __future__ import annotations

from dataclasses import replace

from conftest import (
    clean_anchor,
    clean_reconciliation,
    final_state,
    info_create,
    ok_create,
)

from chaos.domain.checker import CheckReport, Property, check, render
from chaos.domain.history import HistoryEntry, OpKind, Outcome


def _props(history: list[HistoryEntry], final) -> set[Property]:  # type: ignore[no-untyped-def]
    return {v.property for v in check(history, final).violations}


def _detail(report: CheckReport, prop: Property) -> str:
    matches = [v.detail for v in report.violations if v.property is prop]
    assert matches, f"expected a {prop} violation"
    return matches[0]


def _details(report: CheckReport, prop: Property) -> str:
    return " ".join(v.detail for v in report.violations if v.property is prop)


def test_clean_run_is_consistent() -> None:
    history = [ok_create(0, "k0", "t0"), ok_create(1, "k1", "t1")]
    report = check(history, final_state(["t0", "t1"]))
    assert report.ok
    assert report.violations == ()


def test_p1_lost_acknowledged_write() -> None:
    history = [ok_create(0, "k0", "t0"), ok_create(1, "k1", "t1")]
    # t1 acknowledged with 201 but gone after the fault: committed-write loss.
    report = check(history, final_state(["t0"]))
    assert not report.ok
    assert Property.P1_DURABILITY in _props(history, final_state(["t0"]))


def test_p1_ignores_info_creates() -> None:
    # an INFO create may or may not have landed, so its absence is allowed.
    history = [info_create(0, "k0")]
    assert check(history, final_state([])).ok


def test_p1_ok_create_without_id_flags() -> None:
    bad = replace(ok_create(0, "k0", "t0"), returned_id=None)
    report = check([bad], final_state([]))
    assert Property.P1_DURABILITY in {v.property for v in report.violations}


def test_p2_same_key_two_ids_is_duplication() -> None:
    # the retry produced a second transaction for one idempotency key.
    history = [ok_create(0, "dup", "t0"), ok_create(1, "dup", "t1")]
    report = check(history, final_state(["t0", "t1"]))
    assert Property.P2_IDEMPOTENCY in {v.property for v in report.violations}


def test_p2_same_key_same_id_is_fine() -> None:
    history = [ok_create(0, "dup", "t0"), ok_create(1, "dup", "t0")]
    assert check(history, final_state(["t0"])).ok


def test_p3_unbalanced_posting_group() -> None:
    recon = replace(clean_reconciliation(), unbalanced_posting_groups=("g1",))
    report = check([], final_state([], reconciliation=recon))
    assert Property.P3_ATOMICITY in {v.property for v in report.violations}


def test_p3_amount_mismatch() -> None:
    recon = replace(clean_reconciliation(), amount_mismatched_transactions=("t9",))
    report = check([], final_state([], reconciliation=recon))
    assert Property.P3_ATOMICITY in {v.property for v in report.violations}


def test_p4_global_imbalance() -> None:
    recon = replace(clean_reconciliation(), global_balanced=False)
    report = check([], final_state([], reconciliation=recon))
    assert Property.P4_GLOBAL_BALANCE in {v.property for v in report.violations}


def test_p5_snapshot_drift() -> None:
    recon = replace(clean_reconciliation(), snapshot_drift=("PLATFORM/x/USD ...",))
    report = check([], final_state([], reconciliation=recon))
    assert Property.P5_SNAPSHOT in {v.property for v in report.violations}


def test_p6_anchor_unhealthy() -> None:
    anchor = replace(clean_anchor(), healthy=False)
    report = check([], final_state([], anchor=anchor))
    assert Property.P6_ANCHOR in {v.property for v in report.violations}


def test_p6_anchor_failure_listed() -> None:
    anchor = replace(clean_anchor(), failures=("epoch 3 ROOT_MISMATCH: ...",))
    report = check([], final_state([], anchor=anchor))
    assert Property.P6_ANCHOR in {v.property for v in report.violations}


def test_p7_stuck_transaction() -> None:
    recon = replace(clean_reconciliation(), stuck_transactions=("t5",))
    report = check([], final_state([], reconciliation=recon))
    assert Property.P7_CONVERGENCE in {v.property for v in report.violations}


def test_p7_missing_ledger_entries() -> None:
    recon = replace(
        clean_reconciliation(), transactions_without_ledger_entries=("t6",)
    )
    report = check([], final_state([], reconciliation=recon))
    assert Property.P7_CONVERGENCE in {v.property for v in report.violations}


def test_multiple_faults_reported_together() -> None:
    history = [ok_create(0, "dup", "t0"), ok_create(1, "dup", "t1")]
    recon = replace(clean_reconciliation(), global_balanced=False)
    report = check(history, final_state([], reconciliation=recon))
    props = {v.property for v in report.violations}
    assert Property.P1_DURABILITY in props
    assert Property.P2_IDEMPOTENCY in props
    assert Property.P4_GLOBAL_BALANCE in props


def test_fail_outcome_create_is_ignored_by_p1() -> None:
    # a clean 4xx create definitely did not take effect, so its absence is fine.
    rejected = HistoryEntry(
        index=0,
        kind=OpKind.CREATE,
        idempotency_key="k",
        outcome=Outcome.FAIL,
        http_status=400,
    )
    assert check([rejected], final_state([])).ok


def test_p1_detail_names_lost_transaction() -> None:
    report = check([ok_create(0, "k0", "t1")], final_state([]))
    assert "t1" in _detail(report, Property.P1_DURABILITY)


def test_p1_missing_id_detail_is_present() -> None:
    bad = replace(ok_create(0, "k0", "t0"), returned_id=None)
    report = check([bad], final_state([]))
    assert "transaction id" in _detail(report, Property.P1_DURABILITY)


def test_p1_scans_past_skipped_entries() -> None:
    # a skipped entry precedes a lost OK create: the loop must `continue`, not
    # `break`, or the later lost write goes unreported.
    rejected = HistoryEntry(
        index=0,
        kind=OpKind.CREATE,
        idempotency_key="k0",
        outcome=Outcome.FAIL,
        http_status=400,
    )
    report = check([rejected, ok_create(1, "k1", "t1")], final_state([]))
    assert Property.P1_DURABILITY in {v.property for v in report.violations}


def test_p2_detail_names_key() -> None:
    history = [ok_create(0, "dup", "t0"), ok_create(1, "dup", "t1")]
    report = check(history, final_state(["t0", "t1"]))
    assert "dup" in _detail(report, Property.P2_IDEMPOTENCY)


def test_p2_counts_only_ok_creates() -> None:
    # an INFO create (unknown, may carry a stale id) and a FAIL create share the
    # OK create's key. Only the OK create counts toward duplication.
    ok = ok_create(0, "dup", "t1")
    info = HistoryEntry(
        index=1,
        kind=OpKind.CREATE,
        idempotency_key="dup",
        outcome=Outcome.INFO,
        http_status=None,
        returned_id="t2",
    )
    failed = HistoryEntry(
        index=2,
        kind=OpKind.CREATE,
        idempotency_key="dup",
        outcome=Outcome.FAIL,
        http_status=409,
    )
    report = check([ok, info, failed], final_state(["t1"]))
    assert not any(
        v.property is Property.P2_IDEMPOTENCY for v in report.violations
    )


def test_p3_details_name_offenders() -> None:
    recon = replace(
        clean_reconciliation(),
        unbalanced_posting_groups=("g1",),
        amount_mismatched_transactions=("t9",),
    )
    report = check([], final_state([], reconciliation=recon))
    details = _details(report, Property.P3_ATOMICITY)
    assert "g1" in details
    assert "t9" in details


def test_p4_detail_is_exact() -> None:
    recon = replace(clean_reconciliation(), global_balanced=False)
    report = check([], final_state([], reconciliation=recon))
    assert _detail(report, Property.P4_GLOBAL_BALANCE) == (
        "global ledger is not balanced (debits != credits in some currency)"
    )


def test_p5_detail_names_drift_line() -> None:
    recon = replace(
        clean_reconciliation(),
        snapshot_drift=("PLATFORM/acct/USD snapshot=(1,0) ledger=(0,0)",),
    )
    report = check([], final_state([], reconciliation=recon))
    assert "PLATFORM/acct/USD" in _detail(report, Property.P5_SNAPSHOT)


def test_p6_unhealthy_detail_is_exact() -> None:
    anchor = replace(clean_anchor(), healthy=False)
    report = check([], final_state([], anchor=anchor))
    assert (
        _detail(report, Property.P6_ANCHOR)
        == "anchor verification reported unhealthy"
    )


def test_p6_failure_detail_names_epoch() -> None:
    anchor = replace(
        clean_anchor(), failures=("epoch 3 ROOT_MISMATCH: expected=a actual=b",)
    )
    report = check([], final_state([], anchor=anchor))
    assert "epoch 3 ROOT_MISMATCH" in _details(report, Property.P6_ANCHOR)


def test_p7_details_name_transactions() -> None:
    recon = replace(
        clean_reconciliation(),
        stuck_transactions=("t5",),
        transactions_without_ledger_entries=("t6",),
    )
    report = check([], final_state([], reconciliation=recon))
    details = _details(report, Property.P7_CONVERGENCE)
    assert "t5" in details
    assert "t6" in details


def test_render_clean_output() -> None:
    lines = render(check([], final_state([]))).split("\n")
    assert lines[0] == "properties checked: 7"
    assert lines[-1] == "CONSISTENT"


def test_render_reports_violation_and_verdict() -> None:
    recon = replace(clean_reconciliation(), global_balanced=False)
    lines = render(check([], final_state([], reconciliation=recon))).split("\n")
    assert lines[0] == "properties checked: 7"
    assert lines[2] == (
        "  - P4_global_double_entry_balance: "
        "global ledger is not balanced (debits != credits in some currency)"
    )
    assert lines[-1] == "INCONSISTENT"
