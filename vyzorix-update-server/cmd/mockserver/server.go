package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
)

// server holds the wiring between the in-memory store, the on-disk data
// directory and the HTTP routing layer. There is no other state.
type server struct {
	log        *slog.Logger
	store      *store
	dataDir    string
	strictHMAC bool
}

func newServer(log *slog.Logger, st *store, dataDir string, strictHMAC bool) *server {
	return &server{log: log, store: st, dataDir: dataDir, strictHMAC: strictHMAC}
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()

	// Layer 7 — OTA update (BUILD_ORDER.md Layer 7, UPDATE_MECHANISM.md).
	mux.HandleFunc("/api/v1/version", s.handleVersion)
	mux.HandleFunc("/api/v1/apk/", s.handleAPK)

	// Layer 8 — C2 stack (DEVICE_REGISTRATION.md, COMMAND_SECURITY.md).
	mux.HandleFunc("/v1/device/register", s.handleDeviceRegister)
	mux.HandleFunc("/v1/device/", s.handleDeviceScoped) // /{id}, /{id}/fcm-token, /{id}/status, /{id}/command, /{id}/stream

	// Liveness probe — what UptimeRobot will hit on the real deployment.
	mux.HandleFunc("/healthz", s.handleHealth)

	return s.withRequestLogging(mux)
}

// handleDeviceScoped routes anything under /v1/device/{id}/* by inspecting the
// trailing path segment. This avoids pulling in a router dependency for what is
// genuinely a five-endpoint surface.
func (s *server) handleDeviceScoped(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/v1/device/")
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) == 0 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	deviceID := parts[0]
	tail := ""
	if len(parts) == 2 {
		tail = parts[1]
	}

	switch {
	case tail == "" && r.Method == http.MethodDelete:
		s.handleDeviceDelete(w, r, deviceID)
	case tail == "fcm-token" && r.Method == http.MethodPatch:
		s.handleDeviceFCMToken(w, r, deviceID)
	case tail == "status" && r.Method == http.MethodGet:
		s.handleDeviceStatus(w, r, deviceID)
	case tail == "command" && r.Method == http.MethodPost:
		s.handleDeviceCommand(w, r, deviceID)
	case tail == "stream":
		s.handleDeviceStream(w, r, deviceID)
	default:
		http.NotFound(w, r)
	}
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

func (s *server) withRequestLogging(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		h.ServeHTTP(rec, r)
		s.log.Debug("request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"remote", r.RemoteAddr,
		)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

func (r *statusRecorder) WriteHeader(code int) {
	if r.wroteHeader {
		return
	}
	r.status = code
	r.wroteHeader = true
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if !r.wroteHeader {
		r.wroteHeader = true
	}
	return r.ResponseWriter.Write(b)
}

// Hijack lets the WebSocket upgrade reach the underlying connection. Without
// this, the wrapped ResponseWriter would silently fail the upgrade with 500.
func (r *statusRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	hj, ok := r.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, errors.New("ResponseWriter does not implement http.Hijacker")
	}
	return hj.Hijack()
}

// Flush passes through to the underlying ResponseWriter if it supports it.
func (r *statusRecorder) Flush() {
	if fl, ok := r.ResponseWriter.(http.Flusher); ok {
		fl.Flush()
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{
		"error":   code,
		"message": message,
	})
}
