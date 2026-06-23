from pathlib import Path

from recon.cli import main

SAMPLES = Path(__file__).resolve().parents[1] / "samples"


def test_cli_on_samples_reports_discrepancies_and_exits_nonzero(capsys):
    code = main(
        [
            "--ledger",
            str(SAMPLES / "ledger.csv"),
            "--settlement",
            str(SAMPLES / "settlement.csv"),
            "--as-of",
            "2026-06-23",
            "--window-days",
            "2",
        ]
    )
    out = capsys.readouterr().out
    assert "DISCREPANCIES FOUND" in out
    assert "matched:       6" in out
    assert code == 1


def test_cli_writes_prometheus_metrics(tmp_path, capsys):
    metrics = tmp_path / "recon.prom"
    main(
        [
            "--ledger",
            str(SAMPLES / "ledger.csv"),
            "--settlement",
            str(SAMPLES / "settlement.csv"),
            "--as-of",
            "2026-06-23",
            "--metrics-file",
            str(metrics),
        ]
    )
    body = metrics.read_text()
    assert "reconciliation_clean 0" in body
    assert 'reconciliation_discrepancies_total{type="fee_mismatch"} 1' in body
