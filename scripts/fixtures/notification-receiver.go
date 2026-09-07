// Acceptance-only receiver. Run in the isolated backend network namespace.
// Never logs payloads or secrets; the private evidence directory is required.
package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

func main() {
	if len(os.Args) != 2 {
		panic("private evidence directory required")
	}
	dir := os.Args[1]
	var mu sync.Mutex
	mux := http.NewServeMux()
	mux.HandleFunc("/notify", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(405)
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1024*1024))
		if err != nil {
			w.WriteHeader(413)
			return
		}
		secret, err := os.ReadFile(filepath.Join(dir, "secret"))
		if err != nil {
			w.WriteHeader(503)
			return
		}
		mac := hmac.New(sha256.New, []byte(strings.TrimSpace(string(secret))))
		mac.Write(body)
		expected := "sha256=" + hex.EncodeToString(mac.Sum(nil))
		valid := hmac.Equal([]byte(expected), []byte(r.Header.Get("X-DevPilot-Signature-256")))
		var event struct {
			ID string `json:"id"`
		}
		if json.Unmarshal(body, &event) != nil || event.ID == "" || event.ID != r.Header.Get("X-DevPilot-Delivery") {
			valid = false
		}
		status := 503
		mode, _ := os.ReadFile(filepath.Join(dir, "mode"))
		if strings.TrimSpace(string(mode)) == "success" {
			status = 204
		}
		if !valid {
			status = 401
		}
		hash := sha256.Sum256(body)
		mu.Lock()
		defer mu.Unlock()
		f, err := os.OpenFile(filepath.Join(dir, "received.jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			w.WriteHeader(500)
			return
		}
		err = json.NewEncoder(f).Encode(map[string]any{"eventId": event.ID, "payloadSha256": hex.EncodeToString(hash[:]), "validSignature": valid, "responseCode": status, "at": time.Now().UTC()})
		closeErr := f.Close()
		if err != nil || closeErr != nil {
			w.WriteHeader(500)
			return
		}
		w.WriteHeader(status)
	})
	server := &http.Server{Addr: "127.0.0.1:18889", Handler: mux, ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second}
	fmt.Println("Acceptance receiver listening on loopback :18889")
	if err := server.ListenAndServe(); err != nil {
		panic(err)
	}
}
