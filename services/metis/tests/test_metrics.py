"""Unit tests for the in-process Prometheus metrics registry."""
from __future__ import annotations

from metis.metrics import Metrics


def test_counter_increments_and_renders():
    m = Metrics()
    m.inc("metis_fits_total", model_kind="ARIMA", result="ok")
    m.inc("metis_fits_total", model_kind="ARIMA", result="ok")
    m.inc("metis_fits_total", model_kind="LINEAR", result="ok")
    out = m.render()
    assert 'metis_fits_total{model_kind="ARIMA",result="ok"} 2.0' in out
    assert 'metis_fits_total{model_kind="LINEAR",result="ok"} 1.0' in out


def test_summary_tracks_count_and_sum():
    m = Metrics()
    m.observe("metis_fit_duration_ms", 100.0, model_kind="ARIMA")
    m.observe("metis_fit_duration_ms", 300.0, model_kind="ARIMA")
    out = m.render()
    assert 'metis_fit_duration_ms_count{model_kind="ARIMA"} 2' in out
    assert 'metis_fit_duration_ms_sum{model_kind="ARIMA"} 400.0' in out


def test_unlabelled_counter_renders_without_braces():
    m = Metrics()
    m.inc("metis_simulates_total")
    assert "metis_simulates_total 1.0" in m.render()


def test_empty_registry_renders_empty():
    assert Metrics().render() == ""
