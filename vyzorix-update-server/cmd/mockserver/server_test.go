package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

func newTestStack(t *testing.T) (*httptest.Server, *server) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelInfo}))
	wd, _ := os.Getwd()
	st := newStore(defaultMockSecret)
	srv := newServer(logger, st, wd+"/testdata", false)
	httpSrv := httptest.NewServer(srv.routes())
	t.Cleanup(func() {
		httpSrv.Close()
		st.closeAllWebSockets()
	})
	return httpSrv, srv
}

func TestHealthEndpoint(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	resp, err := http.Get(httpSrv.URL + "/healthz")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status: %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	if string(body) != "ok" {
		t.Fatalf("body: %q", body)
	}
}

func TestVersionEndpoint(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	resp, err := http.Get(httpSrv.URL + "/api/v1/version")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status: %d", resp.StatusCode)
	}
	var v map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&v); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if v["version"] != "1.0.0-mock" {
		t.Fatalf("version: %v", v["version"])
	}
}

func TestApkHEADReturnsSize(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	req, _ := http.NewRequest(http.MethodHead, httpSrv.URL+"/api/v1/apk/vyzorix-audiorouter-mock.apk", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("head: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status: %d", resp.StatusCode)
	}
	if resp.ContentLength != 9 {
		t.Fatalf("content-length: %d", resp.ContentLength)
	}
}

func TestRegisterIdempotency(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	body := []byte(`{"deviceId":"dev-1","firebaseInstallId":"fid-1","fcmToken":"t","appVersion":"1.0.0","deviceClass":"nokia_c22"}`)
	post := func() *http.Response {
		r, err := http.Post(httpSrv.URL+"/v1/device/register", "application/json", bytes.NewReader(body))
		if err != nil {
			t.Fatalf("post: %v", err)
		}
		return r
	}
	first := post()
	if first.StatusCode != http.StatusOK {
		t.Fatalf("first register status: %d", first.StatusCode)
	}
	var resp1 registerResponse
	if err := json.NewDecoder(first.Body).Decode(&resp1); err != nil {
		t.Fatalf("decode: %v", err)
	}
	first.Body.Close()
	if resp1.CommandSecret != defaultMockSecret {
		t.Fatalf("commandSecret: %s", resp1.CommandSecret)
	}

	second := post()
	if second.StatusCode != http.StatusOK {
		t.Fatalf("second register status (expected idempotent OK): %d", second.StatusCode)
	}
	second.Body.Close()
}

func TestRegisterHijackRejected(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	body1 := []byte(`{"deviceId":"dev-1","firebaseInstallId":"fid-1"}`)
	r, _ := http.Post(httpSrv.URL+"/v1/device/register", "application/json", bytes.NewReader(body1))
	if r.StatusCode != http.StatusOK {
		t.Fatalf("first status: %d", r.StatusCode)
	}
	r.Body.Close()

	// Same deviceId, different firebaseInstallId — should 409.
	body2 := []byte(`{"deviceId":"dev-1","firebaseInstallId":"fid-2"}`)
	r2, _ := http.Post(httpSrv.URL+"/v1/device/register", "application/json", bytes.NewReader(body2))
	if r2.StatusCode != http.StatusConflict {
		t.Fatalf("hijack status (expected 409): %d", r2.StatusCode)
	}
	r2.Body.Close()
}

func TestWebSocketRoundTrip(t *testing.T) {
	httpSrv, srv := newTestStack(t)
	body := []byte(`{"deviceId":"dev-ws","firebaseInstallId":"fid-ws"}`)
	r, _ := http.Post(httpSrv.URL+"/v1/device/register", "application/json", bytes.NewReader(body))
	r.Body.Close()

	wsURL := strings.Replace(httpSrv.URL, "http://", "ws://", 1) + "/v1/device/dev-ws/stream"
	c, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()

	// Push a command via the store directly (sidesteps HMAC for the test).
	if !srv.store.dispatch("dev-ws", commandFrame{Type: "command", DispatchID: "d1", Command: "PING"}) {
		t.Fatal("dispatch returned false; expected delivery via WSS")
	}

	_, msg, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var got commandFrame
	if err := json.Unmarshal(msg, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got.Command != "PING" || got.DispatchID != "d1" {
		t.Fatalf("frame: %+v", got)
	}
}

func TestDispatchQueuedWhenOffline(t *testing.T) {
	httpSrv, srv := newTestStack(t)
	body := []byte(`{"deviceId":"dev-off","firebaseInstallId":"fid-off"}`)
	r, _ := http.Post(httpSrv.URL+"/v1/device/register", "application/json", bytes.NewReader(body))
	r.Body.Close()

	if srv.store.dispatch("dev-off", commandFrame{Type: "command", DispatchID: "d-off", Command: "PING"}) {
		t.Fatal("dispatch should return false for offline device")
	}
}
