"""Pure reference model of the payment lifecycle.

Functional core: an executable, independently reviewable restatement of the
JVM semantics. The Hypothesis state machine drives the live API and this model
in lockstep and fails on any divergence, so the model must mirror the server's
DECISION ORDER, not just its happy paths:

- capture/refund check the amount bound against the ledger-derived remaining
  FIRST (422 INVALID_PAYMENT_AMOUNT), and only then attempt the status
  transition (409 INVALID_STATE_TRANSITION). A capture on a CAPTURED payment
  is therefore a 422 (remaining == 0), while a capture on a VOIDED payment is
  a 409 (remaining is fine, the edge is not). Mirrors PaymentService.kt
  (capturePayment/refundPayment: bound check before transitionTo).
- capture lands on CAPTURED exactly when the running total reaches the
  authorized amount, else PARTIALLY_CAPTURED; refund lands on REFUNDED exactly
  when refunds reach the captured total, else PARTIALLY_REFUNDED.
- the transition table is a line-for-line mirror of
  PaymentStatus.canTransitionTo, including the partial self-loops and the
  "once a refund starts, no further capture" rule.

Zero I/O and zero dependencies: this file is the oracle the conformance
verdict rests on, and it is the mutmut target.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum

HTTP_CONFLICT = 409
HTTP_UNPROCESSABLE = 422

INVALID_PAYMENT_AMOUNT = "INVALID_PAYMENT_AMOUNT"
INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
IDEMPOTENCY_KEY_REUSE = "IDEMPOTENCY_KEY_REUSE"


class Status(Enum):
    CREATED = "CREATED"
    PENDING = "PENDING"
    AUTHORIZED = "AUTHORIZED"
    PARTIALLY_CAPTURED = "PARTIALLY_CAPTURED"
    CAPTURED = "CAPTURED"
    SETTLED = "SETTLED"
    FAILED = "FAILED"
    PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED"
    REFUNDED = "REFUNDED"
    VOIDED = "VOIDED"
    EXPIRED = "EXPIRED"


_ALLOWED: dict[Status, frozenset[Status]] = {
    Status.CREATED: frozenset({Status.PENDING, Status.FAILED}),
    Status.PENDING: frozenset({Status.AUTHORIZED, Status.FAILED}),
    Status.AUTHORIZED: frozenset(
        {
            Status.PARTIALLY_CAPTURED,
            Status.CAPTURED,
            Status.FAILED,
            Status.VOIDED,
            Status.EXPIRED,
        }
    ),
    Status.PARTIALLY_CAPTURED: frozenset(
        {
            Status.PARTIALLY_CAPTURED,
            Status.CAPTURED,
            Status.PARTIALLY_REFUNDED,
            Status.REFUNDED,
        }
    ),
    Status.CAPTURED: frozenset(
        {Status.SETTLED, Status.PARTIALLY_REFUNDED, Status.REFUNDED}
    ),
    Status.PARTIALLY_REFUNDED: frozenset(
        {Status.PARTIALLY_REFUNDED, Status.REFUNDED}
    ),
    Status.SETTLED: frozenset(),
    Status.FAILED: frozenset(),
    Status.REFUNDED: frozenset(),
    Status.VOIDED: frozenset(),
    Status.EXPIRED: frozenset(),
}


def can_transition(source: Status, target: Status) -> bool:
    return target in _ALLOWED[source]


@dataclass(frozen=True)
class ModelPayment:
    """Authorized amount plus ledger-derived running totals, in minor units."""

    amount: int
    currency: str
    status: Status
    captured: int = 0
    refunded: int = 0


@dataclass(frozen=True)
class Ok:
    payment: ModelPayment


@dataclass(frozen=True)
class ApiError:
    http_status: int
    code: str


def apply_capture(payment: ModelPayment, amount: int | None) -> Ok | ApiError:
    """None captures the full remaining authorized headroom."""
    remaining = payment.amount - payment.captured
    capture_amount = remaining if amount is None else amount
    if capture_amount <= 0 or capture_amount > remaining:
        return ApiError(HTTP_UNPROCESSABLE, INVALID_PAYMENT_AMOUNT)
    target = (
        Status.CAPTURED
        if payment.captured + capture_amount == payment.amount
        else Status.PARTIALLY_CAPTURED
    )
    if not can_transition(payment.status, target):
        return ApiError(HTTP_CONFLICT, INVALID_STATE_TRANSITION)
    return Ok(
        replace(payment, status=target, captured=payment.captured + capture_amount)
    )


def apply_refund(payment: ModelPayment, amount: int | None) -> Ok | ApiError:
    """None refunds the full captured-but-not-refunded balance."""
    remaining = payment.captured - payment.refunded
    refund_amount = remaining if amount is None else amount
    if refund_amount <= 0 or refund_amount > remaining:
        return ApiError(HTTP_UNPROCESSABLE, INVALID_PAYMENT_AMOUNT)
    target = (
        Status.REFUNDED
        if payment.refunded + refund_amount == payment.captured
        else Status.PARTIALLY_REFUNDED
    )
    if not can_transition(payment.status, target):
        return ApiError(HTTP_CONFLICT, INVALID_STATE_TRANSITION)
    return Ok(
        replace(payment, status=target, refunded=payment.refunded + refund_amount)
    )


def apply_void(payment: ModelPayment) -> Ok | ApiError:
    """VOIDED is reachable from a clean AUTHORIZED only; captures void nothing."""
    if not can_transition(payment.status, Status.VOIDED):
        return ApiError(HTTP_CONFLICT, INVALID_STATE_TRANSITION)
    return Ok(replace(payment, status=Status.VOIDED))
