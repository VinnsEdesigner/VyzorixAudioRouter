package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func newTestStack(t *testing.T) (*httptest.Server, *server) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelInfo}))
	wd, _ := os.Getwd()
	st := newStore(defaultMockSecret)
	srv := newServer(logger, st, wd+"/testdata", testFleetToken, "" /* no dashboard token */)
	httpSrv := httptest.NewServer(srv.routes())
	t.Cleanup(func() {
		httpSrv.Close()
		st.closeAllWebSockets()
	})
	return httpSrv, srv
}

// registerForTest does a real POST /v1/device/register via the running test
// server, ensuring the device row exists and uses the same code path the
// device will exercise in production.
func registerForTest(t *testing.T, httpSrv *httptest.Server, deviceID, firebaseInstallID string) {
	t.Helper()
	body := []byte(`{"deviceId":"` + deviceID + `","firebaseInstallId":"` + firebaseInstallID + `"}`)
	req, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/v1/device/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+testFleetToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		bodyResp, _ := io.ReadAll(resp.Body)
		t.Fatalf("register status: %d body: %s", resp.StatusCode, string(bodyResp))
	}
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

func TestRegister_RejectsMissingFleetToken(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	body := []byte(`{"deviceId":"dev-x","firebaseInstallId":"fid-x"}`)
	r, _ := http.Post(httpSrv.URL+"/v1/device/register", "application/json", bytes.NewReader(body))
	if r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 without fleet token, got %d", r.StatusCode)
	}
	r.Body.Close()
}

func TestRegisterIdempotency(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	body := []byte(`{"deviceId":"dev-1","firebaseInstallId":"fid-1","fcmToken":"t","appVersion":"1.0.0","deviceClass":"nokia_c22"}`)
	post := func() *http.Response {
		req, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/v1/device/register", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+testFleetToken)
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("post: %v", err)
		}
		return r
	}
	first := post()
	if first.StatusCode != http.StatusCreated {
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
	if second.StatusCode != http.StatusCreated {
		t.Fatalf("second register status (expected idempotent 201): %d", second.StatusCode)
	}
	second.Body.Close()
}

func TestRegisterHijackRejected(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	registerForTest(t, httpSrv, "dev-1", "fid-1")

	// Same deviceId, different firebaseInstallId — should 409.
	body2 := []byte(`{"deviceId":"dev-1","firebaseInstallId":"fid-2"}`)
	req, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/v1/device/register", bytes.NewReader(body2))
	req.Header.Set("Authorization", "Bearer "+testFleetToken)
	r2, _ := http.DefaultClient.Do(req)
	if r2.StatusCode != http.StatusConflict {
		t.Fatalf("hijack status (expected 409): %d", r2.StatusCode)
	}
	r2.Body.Close()
}

func TestFCMTokenPatch_HappyPath(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	registerForTest(t, httpSrv, "dev-fcm", "fid-fcm")

	body := []byte(`{"fcmToken":"refreshed-token"}`)
	sig, dev, nonce, ts := signRESTRequest(t, defaultMockSecret, "dev-fcm", body)

	req, _ := http.NewRequest(http.MethodPatch, httpSrv.URL+"/v1/device/dev-fcm/fcm-token", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(headerHMAC, sig)
	req.Header.Set(headerDeviceID, dev)
	req.Header.Set(headerNonce, nonce)
	req.Header.Set(headerTimestamp, ts)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("patch: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		t.Fatalf("status: %d body: %s", resp.StatusCode, string(respBody))
	}
}

func TestFCMTokenPatch_RejectsBadHMAC(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	registerForTest(t, httpSrv, "dev-fcm", "fid-fcm")

	body := []byte(`{"fcmToken":"refreshed-token"}`)
	req, _ := http.NewRequest(http.MethodPatch, httpSrv.URL+"/v1/device/dev-fcm/fcm-token", bytes.NewReader(body))
	req.Header.Set(headerHMAC, "0000000000000000000000000000000000000000000000000000000000000000")
	req.Header.Set(headerDeviceID, "dev-fcm")
	req.Header.Set(headerNonce, "n-bad")
	req.Header.Set(headerTimestamp, strconv.FormatInt(time.Now().UnixMilli(), 10))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("patch: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 on bad hmac, got %d", resp.StatusCode)
	}
}

func TestWebSocketRoundTrip(t *testing.T) {
	httpSrv, srv := newTestStack(t)
	registerForTest(t, httpSrv, "dev-ws", "fid-ws")

	// Construct the CONNECT-style handshake HMAC.
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce := "n-ws-rt"
	canonical := buildConnectCanonical("dev-ws", ts, nonce)
	sig := signCanonicalForTest(t, defaultMockSecret, canonical)

	hdr := http.Header{}
	hdr.Set(headerHMAC, sig)
	hdr.Set(headerDeviceID, "dev-ws")
	hdr.Set(headerNonce, nonce)
	hdr.Set(headerTimestamp, ts)

	wsURL := strings.Replace(httpSrv.URL, "http://", "ws://", 1) + "/v1/device/dev-ws/stream"
	c, _, err := websocket.DefaultDialer.Dial(wsURL, hdr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()

	// Server signs the outbound CommandFrame; we re-verify it on receipt.
	frame := commandFrame{
		TransactionID: "tx-ws-1",
		DeviceID:      "dev-ws",
		Action:        "PING",
		TimestampMs:   time.Now().UnixMilli(),
		Nonce:         "n-cmd-1",
	}
	frame.HMAC = signCanonicalHex(defaultMockSecret, buildCommandFrameCanonical(&frame))
	if !srv.store.dispatch("dev-ws", frame) {
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
	if got.Action != "PING" || got.TransactionID != "tx-ws-1" || got.HMAC != frame.HMAC {
		t.Fatalf("frame on wire diverged from what was sent: %+v", got)
	}
	// And the frame the device received re-validates locally.
	if err := srv.verifyCommandFrame(&got, defaultMockSecret); err != nil {
		t.Fatalf("device-side verify failed: %v", err)
	}
}

func TestWebSocket_RejectsUnauthenticatedUpgrade(t *testing.T) {
	httpSrv, _ := newTestStack(t)
	registerForTest(t, httpSrv, "dev-ws2", "fid-ws2")

	wsURL := strings.Replace(httpSrv.URL, "http://", "ws://", 1) + "/v1/device/dev-ws2/stream"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		t.Fatal("expected upgrade to fail without HMAC headers")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got resp=%v err=%v", resp, err)
	}
}

func TestDispatchQueuedWhenOffline(t *testing.T) {
	httpSrv, srv := newTestStack(t)
	registerForTest(t, httpSrv, "dev-off", "fid-off")

	frame := commandFrame{
		TransactionID: "tx-off",
		DeviceID:      "dev-off",
		Action:        "PING",
		TimestampMs:   time.Now().UnixMilli(),
		Nonce:         "n-off",
	}
	if srv.store.dispatch("dev-off", frame) {
		t.Fatal("dispatch should return false for offline device")
	}
}
