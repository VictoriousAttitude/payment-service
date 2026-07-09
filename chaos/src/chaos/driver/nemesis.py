"""Fault injection via kubectl.

Jepsen's "nemesis": the adversary that perturbs the system mid-workload. Two
faults, each targeting one load-bearing claim of the step-3 deployment:

  kill_app_pod       deletes one payment-service pod. Proves ShedLock hands
                     scheduled work to the survivor and that graceful drain plus
                     readiness-gating loses no acknowledged request (P1, P7).
  kill_db_primary    deletes the CNPG primary, forcing a failover. Proves quorum
                     synchronous replication kept every acknowledged commit, so
                     no 2xx payment is lost on promotion (P1, P4).

SIGKILL, not graceful delete, is the interesting case: `--grace-period=0
--force` skips the preStop drain so the process dies mid-request, which is what
exercises the crash safety nets (ShedLock lockAtMostFor reclaim, WAL replay on
the promoted standby) rather than the happy-path shutdown.

Imperative shell. Requires a live cluster and kubectl on PATH; not unit tested.
"""

from __future__ import annotations

import json
import random
import subprocess
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class NemesisConfig:
    namespace: str = "payments"
    app_selector: str = "app=payment-service"
    db_cluster_label: str = "cnpg.io/cluster=payments-db"
    kubectl: str = "kubectl"


class Nemesis:
    def __init__(self, config: NemesisConfig, rng: random.Random | None = None) -> None:
        self._config = config
        # picking which pod to kill is a test fixture, not a security decision,
        # so a seedable PRNG (reproducible runs) is exactly what is wanted.
        self._rng = rng or random.Random()  # noqa: S311

    def kill_app_pod(self) -> str | None:
        pods = self._pods(self._config.app_selector)
        if not pods:
            return None
        victim = self._rng.choice(pods)
        self._force_delete(victim)
        return victim

    def kill_db_primary(self) -> str | None:
        primary = self._db_primary()
        if primary is None:
            return None
        self._force_delete(primary)
        return primary

    def _db_primary(self) -> str | None:
        selector = f"{self._config.db_cluster_label},cnpg.io/instanceRole=primary"
        pods = self._pods(selector)
        return pods[0] if pods else None

    def _pods(self, selector: str) -> list[str]:
        out = self._run(
            [
                "get",
                "pods",
                "-n",
                self._config.namespace,
                "-l",
                selector,
                "-o",
                "jsonpath={.items[*].metadata.name}",
            ]
        )
        return out.split()

    def _force_delete(self, pod: str) -> None:
        self._run(
            [
                "delete",
                "pod",
                pod,
                "-n",
                self._config.namespace,
                "--grace-period=0",
                "--force",
            ]
        )

    def _run(self, args: list[str]) -> str:
        result = subprocess.run(  # noqa: S603 - args are code-controlled, no shell
            [self._config.kubectl, *args],
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()


def parse_pod_names(jsonpath_or_json: str) -> list[str]:
    """Split a kubectl name list, tolerating either space-joined or JSON array.

    Pulled out as a pure helper so the parsing is testable without a cluster.
    """
    text = jsonpath_or_json.strip()
    if text.startswith("["):
        parsed = json.loads(text)
        return [str(name) for name in parsed]
    return text.split()
