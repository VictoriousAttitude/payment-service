from __future__ import annotations

from conftest import final_state, ok_create
from hypothesis import given
from hypothesis import strategies as st

from chaos.domain.checker import Property, check

# distinct ids so each OK create maps to its own transaction, isolating P1/P2.
_ids = st.lists(
    st.integers(min_value=0, max_value=40).map(lambda n: f"t{n}"),
    unique=True,
    max_size=12,
)


@given(_ids)
def test_all_acknowledged_present_is_consistent(ids: list[str]) -> None:
    # every OK create present, one key each, all reports clean => no violation.
    history = [ok_create(i, f"k{i}", txn_id) for i, txn_id in enumerate(ids)]
    assert check(history, final_state(ids)).ok


@given(_ids, st.data())
def test_dropping_acknowledged_ids_is_exactly_p1(
    ids: list[str], data: st.DataObject
) -> None:
    history = [ok_create(i, f"k{i}", txn_id) for i, txn_id in enumerate(ids)]
    kept = data.draw(st.lists(st.sampled_from(ids), unique=True)) if ids else []
    dropped = set(ids) - set(kept)

    report = check(history, final_state(kept))
    p1 = [v for v in report.violations if v.property is Property.P1_DURABILITY]
    # one lost-write violation per acknowledged id missing from the final state.
    assert len(p1) == len(dropped)


@given(st.text(min_size=1, max_size=8), st.integers(0, 5), st.integers(6, 12))
def test_shared_key_distinct_ids_always_flags_p2(
    key: str, a: int, b: int
) -> None:
    history = [ok_create(0, key, f"t{a}"), ok_create(1, key, f"t{b}")]
    report = check(history, final_state([f"t{a}", f"t{b}"]))
    assert any(v.property is Property.P2_IDEMPOTENCY for v in report.violations)


@given(_ids)
def test_check_is_deterministic(ids: list[str]) -> None:
    history = [ok_create(i, f"k{i}", txn_id) for i, txn_id in enumerate(ids)]
    final = final_state(ids[: len(ids) // 2])
    assert check(history, final).violations == check(history, final).violations
