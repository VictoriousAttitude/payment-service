"""Zero-dependency HTTP client over urllib.

The distinction the whole harness rests on is made here, in `request`: a 2xx is
OK, a clean 4xx is FAIL, and anything else (5xx, a timeout, a dropped socket
because the pod we were talking to just got killed) is INFO. The client never
raises on a transport error; it returns an INFO response so the caller records
the uncertainty rather than crashing the run.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

from chaos.domain.history import Outcome


@dataclass(frozen=True, slots=True)
class Response:
    outcome: Outcome
    status: int | None
    body: dict[str, object] | None
    error: str | None = None


class Client:
    def __init__(self, base_url: str, api_key: str, timeout: float = 5.0) -> None:
        self._base = base_url.rstrip("/")
        self._api_key = api_key
        self._timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        *,
        body: dict[str, object] | None = None,
        idempotency_key: str | None = None,
        authed: bool = True,
    ) -> Response:
        payload = json.dumps(body).encode() if body is not None else None
        # base_url is operator-supplied on the CLI, always http(s), never
        # attacker-controlled: the S310 scheme-audit does not apply here.
        req = urllib.request.Request(  # noqa: S310
            f"{self._base}{path}", data=payload, method=method
        )
        req.add_header("Content-Type", "application/json")
        if authed:
            req.add_header("X-Api-Key", self._api_key)
        if idempotency_key is not None:
            req.add_header("Idempotency-Key", idempotency_key)

        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:  # noqa: S310
                return Response(Outcome.OK, resp.status, _read_json(resp.read()))
        except urllib.error.HTTPError as exc:
            # a clean status from the app: 4xx is a definite rejection, 5xx is
            # unknown because the request may have committed before the error.
            outcome = Outcome.FAIL if 400 <= exc.code < 500 else Outcome.INFO
            return Response(outcome, exc.code, _read_json(exc.read()), str(exc))
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
            # never reached the app or lost the reply: genuinely unknown.
            return Response(Outcome.INFO, None, None, str(exc))


def _read_json(raw: bytes) -> dict[str, object] | None:
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None
