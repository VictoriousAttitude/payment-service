"""Unit tests for the pure reference model. Mutmut target: model.py.

Mutation-hardened on purpose: the transition matrix is duplicated here as a
literal table (kills set-membership mutants in _ALLOWED), error expectations
compare whole ApiError literals (kills 409/422 and code-string mutants), and
every comparison sits on both sides of its boundary (kills <=/</==/!= swaps).
"""

from __future__ import annotations

import pytest

from mbt.model import (
    ApiError,
    ModelPayment,
    Ok,
    Status,
    apply_capture,
    apply_refund,
    apply_void,
    can_transition,
)

AMOUNT_ERROR = ApiError(422, "INVALID_PAYMENT_AMOUNT")
STATE_ERROR = ApiError(409, "INVALID_STATE_TRANSITION")


def pay(
    status: Status,
    amount: int = 100,
    captured: int = 0,
    refunded: int = 0,
) -> ModelPayment:
    return ModelPayment(
        amount=amount,
        currency="USD",
        status=status,
        captured=captured,
        refunded=refunded,
    )


# Literal duplicate of PaymentStatus.canTransitionTo — intentionally NOT
# imported from the source module, so any mutation of _ALLOWED diverges.
EXPECTED_EDGES: dict[Status, set[Status]] = {
    Status.CREATED: {Status.PENDING, Status.FAILED},
    Status.PENDING: {Status.AUTHORIZED, Status.FAILED},
    Status.AUTHORIZED: {
        Status.PARTIALLY_CAPTURED,
        Status.CAPTURED,
        Status.FAILED,
        Status.VOIDED,
        Status.EXPIRED,
    },
    Status.PARTIALLY_CAPTURED: {
        Status.PARTIALLY_CAPTURED,
        Status.CAPTURED,
        Status.PARTIALLY_REFUNDED,
        Status.REFUNDED,
    },
    Status.CAPTURED: {Status.SETTLED, Status.PARTIALLY_REFUNDED, Status.REFUNDED},
    Status.PARTIALLY_REFUNDED: {Status.PARTIALLY_REFUNDED, Status.REFUNDED},
    Status.SETTLED: set(),
    Status.FAILED: set(),
    Status.REFUNDED: set(),
    Status.VOIDED: set(),
    Status.EXPIRED: set(),
}


@pytest.mark.parametrize("source", list(Status))
@pytest.mark.parametrize("target", list(Status))
def test_transition_matrix(source: Status, target: Status) -> None:
    assert can_transition(source, target) == (target in EXPECTED_EDGES[source])


@pytest.mark.parametrize("status", list(Status))
def test_status_values_mirror_server_labels(status: Status) -> None:
    # The machine parses server JSON via Status(value); names ARE the wire form.
    assert status.value == status.name


# --- capture ---------------------------------------------------------------


def test_capture_none_takes_full_remaining() -> None:
    result = apply_capture(pay(Status.AUTHORIZED, amount=100), None)
    assert result == Ok(pay(Status.CAPTURED, amount=100, captured=100))


def test_capture_partial_lands_partially_captured() -> None:
    result = apply_capture(pay(Status.AUTHORIZED, amount=100), 40)
    assert result == Ok(pay(Status.PARTIALLY_CAPTURED, amount=100, captured=40))


def test_capture_second_partial_accumulates() -> None:
    start = pay(Status.PARTIALLY_CAPTURED, amount=100, captured=40)
    result = apply_capture(start, 10)
    assert result == Ok(pay(Status.PARTIALLY_CAPTURED, amount=100, captured=50))


def test_capture_exact_remaining_completes() -> None:
    start = pay(Status.PARTIALLY_CAPTURED, amount=100, captured=40)
    result = apply_capture(start, 60)
    assert result == Ok(pay(Status.CAPTURED, amount=100, captured=100))


def test_capture_none_on_partial_takes_rest() -> None:
    start = pay(Status.PARTIALLY_CAPTURED, amount=100, captured=40)
    result = apply_capture(start, None)
    assert result == Ok(pay(Status.CAPTURED, amount=100, captured=100))


def test_capture_over_remaining_is_amount_error() -> None:
    start = pay(Status.PARTIALLY_CAPTURED, amount=100, captured=40)
    assert apply_capture(start, 61) == AMOUNT_ERROR


def test_capture_zero_is_amount_error() -> None:
    assert apply_capture(pay(Status.AUTHORIZED), 0) == AMOUNT_ERROR


def test_capture_negative_is_amount_error() -> None:
    assert apply_capture(pay(Status.AUTHORIZED), -5) == AMOUNT_ERROR


def test_capture_of_one_minor_unit_is_valid() -> None:
    # boundary partner of the zero test: kills <=0 -> <=1 mutants
    result = apply_capture(pay(Status.AUTHORIZED, amount=100), 1)
    assert result == Ok(pay(Status.PARTIALLY_CAPTURED, amount=100, captured=1))


def test_capture_on_captured_is_amount_error_not_state_error() -> None:
    # remaining == 0 fires the 422 bound check BEFORE any transition check,
    # mirroring PaymentService.capturePayment's decision order.
    exhausted = pay(Status.CAPTURED, amount=100, captured=100)
    assert apply_capture(exhausted, None) == AMOUNT_ERROR
    assert apply_capture(exhausted, 1) == AMOUNT_ERROR


def test_capture_on_voided_is_state_error() -> None:
    # remaining is the full amount (nothing captured), so the bound check
    # passes and the missing VOIDED->*CAPTURED edge is what rejects it.
    assert apply_capture(pay(Status.VOIDED, amount=100), 10) == STATE_ERROR


def test_capture_on_failed_is_state_error() -> None:
    assert apply_capture(pay(Status.FAILED, amount=100), None) == STATE_ERROR


def test_capture_after_refund_started_is_state_error() -> None:
    # capture and refund are sequential phases: headroom remains (60) but
    # there is no PARTIALLY_REFUNDED -> capture edge.
    start = pay(Status.PARTIALLY_REFUNDED, amount=100, captured=40, refunded=10)
    assert apply_capture(start, 10) == STATE_ERROR


def test_capture_preserves_refunded_and_identity_fields() -> None:
    start = ModelPayment(
        amount=100, currency="JPY", status=Status.AUTHORIZED, captured=0, refunded=0
    )
    result = apply_capture(start, 30)
    assert result == Ok(
        ModelPayment(
            amount=100,
            currency="JPY",
            status=Status.PARTIALLY_CAPTURED,
            captured=30,
            refunded=0,
        )
    )


# --- refund ----------------------------------------------------------------


def test_refund_none_takes_full_captured_balance() -> None:
    start = pay(Status.CAPTURED, amount=100, captured=100)
    result = apply_refund(start, None)
    assert result == Ok(pay(Status.REFUNDED, amount=100, captured=100, refunded=100))


def test_refund_partial_lands_partially_refunded() -> None:
    start = pay(Status.CAPTURED, amount=100, captured=100)
    result = apply_refund(start, 30)
    assert result == Ok(
        pay(Status.PARTIALLY_REFUNDED, amount=100, captured=100, refunded=30)
    )


def test_refund_second_partial_accumulates() -> None:
    start = pay(Status.PARTIALLY_REFUNDED, amount=100, captured=100, refunded=30)
    result = apply_refund(start, 20)
    assert result == Ok(
        pay(Status.PARTIALLY_REFUNDED, amount=100, captured=100, refunded=50)
    )


def test_refund_exact_remaining_completes() -> None:
    start = pay(Status.PARTIALLY_REFUNDED, amount=100, captured=100, refunded=30)
    result = apply_refund(start, 70)
    assert result == Ok(pay(Status.REFUNDED, amount=100, captured=100, refunded=100))


def test_refund_of_partial_capture_bounded_by_captured_not_amount() -> None:
    start = pay(Status.PARTIALLY_CAPTURED, amount=100, captured=40)
    assert apply_refund(start, 41) == AMOUNT_ERROR
    result = apply_refund(start, 40)
    assert result == Ok(pay(Status.REFUNDED, amount=100, captured=40, refunded=40))


def test_refund_over_remaining_is_amount_error() -> None:
    start = pay(Status.PARTIALLY_REFUNDED, amount=100, captured=100, refunded=30)
    assert apply_refund(start, 71) == AMOUNT_ERROR


def test_refund_zero_is_amount_error() -> None:
    start = pay(Status.CAPTURED, amount=100, captured=100)
    assert apply_refund(start, 0) == AMOUNT_ERROR


def test_refund_negative_is_amount_error() -> None:
    start = pay(Status.CAPTURED, amount=100, captured=100)
    assert apply_refund(start, -5) == AMOUNT_ERROR


def test_refund_of_one_minor_unit_is_valid() -> None:
    # boundary partner of the zero test: kills <=0 -> <=1 mutants
    start = pay(Status.CAPTURED, amount=100, captured=100)
    result = apply_refund(start, 1)
    assert result == Ok(
        pay(Status.PARTIALLY_REFUNDED, amount=100, captured=100, refunded=1)
    )


def test_refund_on_authorized_is_amount_error() -> None:
    # nothing captured -> remaining refundable is 0 -> 422, never a 409;
    # mirrors the server bounding refunds by the ledger before the transition.
    assert apply_refund(pay(Status.AUTHORIZED, amount=100), None) == AMOUNT_ERROR
    assert apply_refund(pay(Status.AUTHORIZED, amount=100), 10) == AMOUNT_ERROR


def test_refund_on_refunded_is_amount_error() -> None:
    done = pay(Status.REFUNDED, amount=100, captured=100, refunded=100)
    assert apply_refund(done, None) == AMOUNT_ERROR
    assert apply_refund(done, 1) == AMOUNT_ERROR


def test_refund_on_settled_is_state_error() -> None:
    # refundable money exists but SETTLED is absorbing: bound passes, edge fails.
    start = pay(Status.SETTLED, amount=100, captured=100)
    assert apply_refund(start, 10) == STATE_ERROR


def test_refund_preserves_captured_and_identity_fields() -> None:
    start = ModelPayment(
        amount=100, currency="EUR", status=Status.CAPTURED, captured=100, refunded=0
    )
    result = apply_refund(start, 25)
    assert result == Ok(
        ModelPayment(
            amount=100,
            currency="EUR",
            status=Status.PARTIALLY_REFUNDED,
            captured=100,
            refunded=25,
        )
    )


# --- void ------------------------------------------------------------------


def test_void_from_authorized() -> None:
    result = apply_void(pay(Status.AUTHORIZED, amount=100))
    assert result == Ok(pay(Status.VOIDED, amount=100))


@pytest.mark.parametrize(
    "status", [s for s in Status if s is not Status.AUTHORIZED]
)
def test_void_from_anywhere_else_is_state_error(status: Status) -> None:
    start = pay(status, amount=100, captured=10, refunded=5)
    assert apply_void(start) == STATE_ERROR
