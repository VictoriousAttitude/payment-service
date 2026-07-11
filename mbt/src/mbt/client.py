"""Thin HTTP client for the conformance suite.

Imperative shell. Returns the EXACT status code plus the parsed body: the
whole point of conformance testing is comparing precise API behavior (200 vs
201, 409 vs 422, error codes) against the model, so unlike the chaos client
there is no ok/fail/info trichotomy. Transport errors raise: conformance
assumes a healthy server; a dropped socket means the run is invalid, not that
the API misbehaved.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True)
class Response:
    status: int
    body: dict[str, object]


class Client:
    def __init__(self, base_url: str, api_key: str, timeout: float = 10.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None = None,
        idempotency_key: str | None = None,
    ) -> Response:
        headers = {"X-Api-Key": self._api_key}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if idempotency_key is not None:
            headers["Idempotency-Key"] = idempotency_key
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(  # noqa: S310 operator-supplied http url
            self._base_url + path, data=data, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(  # noqa: S310
                request, timeout=self._timeout
            ) as response:
                return Response(response.status, _parse(response.read()))
        except urllib.error.HTTPError as e:
            # 4xx/5xx: still a definitive API answer, not a transport failure
            return Response(e.code, _parse(e.read()))


def _parse(raw: bytes) -> dict[str, object]:
    if not raw:
        return {}
    parsed = json.loads(raw)
    return parsed if isinstance(parsed, dict) else {}
