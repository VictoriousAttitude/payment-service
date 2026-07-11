"""Append-only JSONL history recorder.

The history is written as it happens, one JSON object per line, flushed after
every op. If the harness process itself is killed (or the machine running it
dies) the partial history up to the last completed op survives on disk, so the
run is still analysable. JSONL over a single JSON array precisely because a
half-written array is unparseable but a half-written JSONL file is just short.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path
from types import TracebackType

from chaos.domain.history import HistoryEntry


class HistoryWriter:
    def __init__(self, path: Path) -> None:
        self._path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = path.open("w", encoding="utf-8")

    def append(self, entry: HistoryEntry) -> None:
        self._handle.write(json.dumps(entry.to_json(), separators=(",", ":")))
        self._handle.write("\n")
        self._handle.flush()

    def close(self) -> None:
        self._handle.close()

    def __enter__(self) -> HistoryWriter:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()


def read_history(path: Path) -> list[HistoryEntry]:
    entries: list[HistoryEntry] = []
    for line in _nonempty_lines(path):
        entries.append(HistoryEntry.from_json(json.loads(line)))
    return entries


def _nonempty_lines(path: Path) -> Iterator[str]:
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                yield stripped
