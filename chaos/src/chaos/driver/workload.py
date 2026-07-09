"""Concurrent workload generator.

Fires a stream of create/capture operations against the service while the
nemesis is injecting faults, and records every attempt as a HistoryEntry. The
one behaviour that makes the history checkable is the INFO retry: when an op
comes back unknown, the SAME idempotency key is reissued, which is the only
safe thing a real client can do and is exactly what P2 (no duplication) exists
to gate.

This is imperative shell. It needs the live step-3 cluster and is not unit
tested; the correctness lives in the pure checker it feeds.
"""

from __future__ import annotations

import threading
import uuid
from collections.abc import Callable

from chaos.adapters.recorder import HistoryWriter
from chaos.domain.history import HistoryEntry, OpKind, Outcome
from chaos.driver.client import Client, Response


class Workload:
    def __init__(
        self,
        client: Client,
        merchant_id: str,
        writer: HistoryWriter,
        *,
        currency: str = "USD",
        amount: int = 1000,
        max_info_retries: int = 3,
    ) -> None:
        self._client = client
        self._merchant_id = merchant_id
        self._writer = writer
        self._currency = currency
        self._amount = amount
        self._max_info_retries = max_info_retries
        self._counter = 0
        self._lock = threading.Lock()

    def run(self, operations: int, threads: int) -> None:
        remaining = threading.Semaphore(0)
        for _ in range(operations):
            remaining.release()

        workers = [
            threading.Thread(target=self._worker, args=(remaining,), daemon=True)
            for _ in range(threads)
        ]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()

    def _worker(self, remaining: threading.Semaphore) -> None:
        while remaining.acquire(blocking=False):
            self._create_with_retry()

    def _create_with_retry(self) -> None:
        key = f"chaos-{uuid.uuid4()}"
        body: dict[str, object] = {
            "merchantId": self._merchant_id,
            "amount": self._amount,
            "currency": self._currency,
        }
        # reissue the SAME key on INFO so a committed-but-unacknowledged write
        # is deduplicated by the server, never double-created.
        self._attempt(
            OpKind.CREATE,
            key,
            lambda: self._client.request(
                "POST", "/api/v1/payments", body=body, idempotency_key=key
            ),
        )

    def _attempt(
        self, kind: OpKind, key: str, call: Callable[[], Response]
    ) -> None:
        attempts = 0
        while True:
            response = call()
            self._record(kind, key, response)
            if response.outcome is not Outcome.INFO:
                return
            attempts += 1
            if attempts > self._max_info_retries:
                return

    def _record(self, kind: OpKind, key: str, response: Response) -> None:
        returned_id = None
        if response.outcome is Outcome.OK and response.body is not None:
            raw = response.body.get("id")
            returned_id = raw if isinstance(raw, str) else None
        # one lock guards both the index and the file write, so the JSONL file
        # is never interleaved by two worker threads.
        with self._lock:
            index = self._counter
            self._counter += 1
            self._writer.append(
                HistoryEntry(
                    index=index,
                    kind=kind,
                    idempotency_key=key,
                    outcome=response.outcome,
                    http_status=response.status,
                    returned_id=returned_id,
                    amount=self._amount,
                    currency=self._currency,
                )
            )
