"""Acquirer settlement-file simulator (procsim).

Turns a ledger extract into the acquirer's settlement CSV and injects seeded
faults, so both reconciliation implementations (the JVM ingester and the
Python oracle) can be proven to catch every fault class. Test-data generator,
deliberately outside the mutmut gate: its correctness is established by the
round-trip property tests, not by mutation coverage.
"""
