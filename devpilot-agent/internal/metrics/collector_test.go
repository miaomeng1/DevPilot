package metrics

import (
	"testing"
	"time"
)

func TestRatesHandleFirstSampleAndCounterReset(t *testing.T) {
	collector := NewCollector()
	start := time.Unix(100, 0)
	upload, download := collector.rates(100, 200, start)
	if upload != 0 || download != 0 {
		t.Fatalf("first rates = %v/%v, want zero", upload, download)
	}
	upload, download = collector.rates(300, 500, start.Add(10*time.Second))
	if upload != 20 || download != 30 {
		t.Fatalf("second rates = %v/%v, want 20/30", upload, download)
	}
	upload, download = collector.rates(10, 20, start.Add(20*time.Second))
	if upload != 0 || download != 0 {
		t.Fatalf("reset rates = %v/%v, want zero", upload, download)
	}
}
