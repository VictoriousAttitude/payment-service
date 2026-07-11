"""Live conformance: Hypothesis stateful machine vs the pure reference model.

Drives the real HTTP API and the model in lockstep. Hypothesis searches the
operation-sequence space (interleaved partial captures/refunds, voids,
idempotency replays and conflicts, across several payments at once) and
shrinks any divergence to a minimal reproducer.

Requires a running payment-service with the seeded dev merchant (repo root:
`docker compose up -d --build`). The module skips itself when nothing answers
at MBT_BASE_URL so the default pytest run stays green without a server.

Nondeterminism: the provider simulator authorizes ~90% of creates at random.
The authorization outcome is treated as an environmental INPUT — after create
the machine polls until the payment stabilizes to AUTHORIZED or FAILED and
records which. Everything downstream of that observation is deterministic
and checked exactly.
"""

from __future__ import annotations

import os
import time
import uuid

import pytest
from hypothesis import HealthCheck, settings
from hypothesis import strategies as st
from hypothesis.stateful import Bundle, RuleBasedStateMachine, invariant, rule

from mbt.client import Client, Response
from mbt.model import (
    IDEMPOTENCY_KEY_REUSE,
    ApiError,
    ModelPayment,
    Ok,
    Status,
    apply_capture,
    apply_refund,
    apply_void,
)

BASE_URL = os.environ.get("MBT_BASE_URL", "http://localhost:8080")
API_KEY = os.environ.get("MBT_API_KEY", "test-api-key-123")
MERCHANT_ID = os.environ.get(
    "MBT_MERCHANT_ID", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"
)

STABLE = frozenset({Status.AUTHORIZED, Status.FAILED})
TRANSIENT = frozenset({Status.CREATED, Status.PENDING})

# The outbox dispatcher drains every 2s (first run 5s after boot) and the
# simulator adds 500ms latency, so a create normally stabilizes within ~3-8s;
# 30s spans several dispatch rounds plus retry backoff.
AUTHORIZATION_DEADLINE_S = 30.0
POLL_INTERVAL_S = 0.5


def _server_up() -> bool:
    try:
        Client(BASE_URL, API_KEY, timeout=2.0).request("GET", "/actuator/health")
    except OSError:
        return False
    return True


pytestmark = pytest.mark.skipif(
    not _server_up(), reason=f"no payment-service listening at {BASE_URL}"
)


def _amount_choice(remaining: int) -> st.SearchStrategy[int | None]:
    """None (the rest), a valid partial/exact amount, or a deliberate over-ask.

    Always >= 1 when explicit: non-positive amounts are rejected by bean
    validation (400) before the domain logic the model mirrors is reached.
    """
    choices: list[st.SearchStrategy[int | None]] = [
        st.none(),
        st.just(remaining + 1),
    ]
    if remaining >= 1:
        choices.append(st.integers(min_value=1, max_value=remaining))
    return st.one_of(choices)


class PaymentConformance(RuleBasedStateMachine):
    payments: Bundle[str] = Bundle("payments")

    def __init__(self) -> None:
        super().__init__()
        self.client = Client(BASE_URL, API_KEY)
        self.model: dict[str, ModelPayment] = {}
        self.create_keys: dict[str, str] = {}

    # --- rules ---------------------------------------------------------

    @rule(
        target=payments,
        amount=st.integers(min_value=1, max_value=100_000),
        currency=st.sampled_from(["USD", "EUR", "JPY"]),
    )
    def create_payment(self, amount: int, currency: str) -> str:
        key = f"mbt-{uuid.uuid4()}"
        response = self.client.request(
            "POST",
            "/api/v1/payments",
            body=self._create_payload(amount, currency),
            idempotency_key=key,
        )
        assert response.status == 201, (response.status, response.body)
        payment_id = str(response.body["id"])
        status = self._await_stable(payment_id)
        self.model[payment_id] = ModelPayment(
            amount=amount, currency=currency, status=status
        )
        self.create_keys[payment_id] = key
        return payment_id

    @rule(payment_id=payments)
    def replay_create_returns_same_payment(self, payment_id: str) -> None:
        current = self.model[payment_id]
        response = self.client.request(
            "POST",
            "/api/v1/payments",
            body=self._create_payload(current.amount, current.currency),
            idempotency_key=self.create_keys[payment_id],
        )
        assert response.status == 200, (response.status, response.body)
        assert str(response.body["id"]) == payment_id

    @rule(payment_id=payments)
    def conflicting_replay_is_rejected(self, payment_id: str) -> None:
        current = self.model[payment_id]
        response = self.client.request(
            "POST",
            "/api/v1/payments",
            body=self._create_payload(current.amount + 1, current.currency),
            idempotency_key=self.create_keys[payment_id],
        )
        assert response.status == 422, (response.status, response.body)
        assert response.body.get("error") == IDEMPOTENCY_KEY_REUSE

    @rule(payment_id=payments, data=st.data())
    def capture(self, payment_id: str, data: st.DataObject) -> None:
        current = self.model[payment_id]
        amount = data.draw(
            _amount_choice(current.amount - current.captured), label="capture amount"
        )
        response = self.client.request(
            "POST",
            f"/api/v1/payments/{payment_id}/capture",
            body={} if amount is None else {"amount": amount},
        )
        self._reconcile(payment_id, apply_capture(current, amount), response)

    @rule(payment_id=payments, data=st.data())
    def refund(self, payment_id: str, data: st.DataObject) -> None:
        current = self.model[payment_id]
        amount = data.draw(
            _amount_choice(current.captured - current.refunded),
            label="refund amount",
        )
        response = self.client.request(
            "POST",
            f"/api/v1/payments/{payment_id}/refund",
            body={} if amount is None else {"amount": amount},
        )
        self._reconcile(payment_id, apply_refund(current, amount), response)

    @rule(payment_id=payments)
    def void(self, payment_id: str) -> None:
        response = self.client.request(
            "POST", f"/api/v1/payments/{payment_id}/void"
        )
        self._reconcile(payment_id, apply_void(self.model[payment_id]), response)

    @rule()
    def non_json_payment_method_is_rejected_at_the_boundary(self) -> None:
        # Found by this suite: `paymentMethod` lands in a jsonb column, and a
        # bare token like "card" used to pass bean validation and 500 at
        # INSERT. The fixed contract is a 400 before any state is touched.
        payload = dict(self._create_payload(100, "USD"), paymentMethod="card")
        response = self.client.request(
            "POST",
            "/api/v1/payments",
            body=payload,
            idempotency_key=f"mbt-{uuid.uuid4()}",
        )
        assert response.status == 400, (response.status, response.body)
        assert response.body.get("error") == "VALIDATION_ERROR"

    @rule()
    def get_unknown_id_is_not_found(self) -> None:
        response = self.client.request("GET", f"/api/v1/payments/{uuid.uuid4()}")
        assert response.status == 404, (response.status, response.body)
        assert response.body.get("error") == "TRANSACTION_NOT_FOUND"

    # --- invariant -------------------------------------------------------

    @invariant()
    def api_agrees_with_model(self) -> None:
        for payment_id in self.model:
            self._assert_matches(payment_id, self._get(payment_id))

    # --- plumbing --------------------------------------------------------

    @staticmethod
    def _create_payload(amount: int, currency: str) -> dict[str, object]:
        return {
            "merchantId": MERCHANT_ID,
            "amount": amount,
            "currency": currency,
            "description": "mbt conformance",
            # the field is persisted into jsonb, so its value must itself be
            # a JSON document (the API 400s anything else)
            "paymentMethod": '{"type": "card"}',
        }

    def _get(self, payment_id: str) -> dict[str, object]:
        response = self.client.request("GET", f"/api/v1/payments/{payment_id}")
        assert response.status == 200, (response.status, response.body)
        return response.body

    def _await_stable(self, payment_id: str) -> Status:
        deadline = time.monotonic() + AUTHORIZATION_DEADLINE_S
        while time.monotonic() < deadline:
            status = Status(str(self._get(payment_id)["status"]))
            if status in STABLE:
                return status
            assert status in TRANSIENT, f"unexpected transient status {status}"
            time.sleep(POLL_INTERVAL_S)
        raise AssertionError(
            f"payment {payment_id} did not reach AUTHORIZED/FAILED "
            f"within {AUTHORIZATION_DEADLINE_S}s"
        )

    def _reconcile(
        self, payment_id: str, expected: Ok | ApiError, response: Response
    ) -> None:
        if isinstance(expected, Ok):
            assert response.status == 200, (expected, response.status, response.body)
            self.model[payment_id] = expected.payment
            self._assert_matches(payment_id, response.body)
        else:
            observed = (response.status, response.body.get("error"))
            assert observed == (expected.http_status, expected.code), (
                expected,
                response.body,
            )
            # a rejected operation must leave the payment untouched
            self._assert_matches(payment_id, self._get(payment_id))

    def _assert_matches(self, payment_id: str, body: dict[str, object]) -> None:
        expected = self.model[payment_id]
        observed = (
            str(body["status"]),
            body["amount"],
            body["capturedAmount"],
            body["refundedAmount"],
            str(body["currency"]),
        )
        assert observed == (
            expected.status.value,
            expected.amount,
            expected.captured,
            expected.refunded,
            expected.currency,
        ), (payment_id, observed, expected)


PaymentConformance.TestCase.settings = settings(
    max_examples=int(os.environ.get("MBT_EXAMPLES", "5")),
    stateful_step_count=int(os.environ.get("MBT_STEPS", "15")),
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow],
    print_blob=True,
)

TestPaymentConformance = PaymentConformance.TestCase
