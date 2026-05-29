package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"testing"
	"time"
)

const testFleetToken = "test-fleet-token"

func newTestServer(t *testing.T) *server {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelInfo}))
	wd, _ := os.Getwd()
	st := newStore(defaultMockSecret)
	return newServer(logger, st, wd+"/testdata", testFleetToken, "" /* no dashboard token */)
}

func mustHex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

// signCanonicalForTest mirrors what the device or server SDK does — used
// purely so tests can produce signatures that the server should accept.
func signCanonicalForTest(t *testing.T, secretHex string, canonical []byte) string {
	t.Helper()
	mac := hmac.New(sha256.New, mustHex(secretHex))
	mac.Write(canonical)
	return hex.EncodeToString(mac.Sum(nil))
}

// signRESTRequest computes the scheme-#2 HMAC: hex(HMAC-SHA256(body)) keyed
// with command_secret. Returns the four headers to attach to the request.
func signRESTRequest(t *testing.T, secretHex, deviceID string, body []byte) (sig, dev, nonce, ts string) {
	t.Helper()
	nonce = "n-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	ts = strconv.FormatInt(time.Now().UnixMilli(), 10)
	sig = signCanonicalForTest(t, secretHex, body)
	dev = deviceID
	return
}

func TestRESTHMAC_HappyPath(t *testing.T) {
	srv := newTestServer(t)
	srv.store.register(registerRequest{DeviceID: "dev-1", FirebaseInstallID: "fid-1"}, time.Now())

	body := []byte(`{"fcmToken":"new-token"}`)
	sig, dev, nonce, ts := signRESTRequest(t, defaultMockSecret, "dev-1", body)

	r := httptest.NewRequest(http.MethodPatch, "/v1/device/dev-1/fcm-token", bytes.NewReader(body))
	r.Header.Set(headerHMAC, sig)
	r.Header.Set(headerDeviceID, dev)
	r.Header.Set(headerNonce, nonce)
	r.Header.Set(headerTimestamp, ts)

	if err := srv.verifyRESTHMAC(r, body, "dev-1", defaultMockSecret); err != nil {
		t.Fatalf("verifyRESTHMAC: %v", err)
	}
}

func TestRESTHMAC_RejectsReplay(t *testing.T) {
	srv := newTestServer(t)
	body := []byte(`{"fcmToken":"x"}`)
	nonce := "n-replay"
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	sig := signCanonicalForTest(t, defaultMockSecret, body)

	mk := func() *http.Request {
		r := httptest.NewRequest(http.MethodPatch, "/v1/device/dev-1/fcm-token", bytes.NewReader(body))
		r.Header.Set(headerHMAC, sig)
		r.Header.Set(headerDeviceID, "dev-1")
		r.Header.Set(headerNonce, nonce)
		r.Header.Set(headerTimestamp, ts)
		return r
	}
	if err := srv.verifyRESTHMAC(mk(), body, "dev-1", defaultMockSecret); err != nil {
		t.Fatalf("first call: %v", err)
	}
	if err := srv.verifyRESTHMAC(mk(), body, "dev-1", defaultMockSecret); err != errNonceReplay {
		t.Fatalf("expected errNonceReplay, got %v", err)
	}
}

func TestRESTHMAC_RejectsStaleTimestamp(t *testing.T) {
	srv := newTestServer(t)
	body := []byte(`{}`)
	stale := strconv.FormatInt(time.Now().Add(-5*time.Minute).UnixMilli(), 10)
	sig := signCanonicalForTest(t, defaultMockSecret, body)

	r := httptest.NewRequest(http.MethodPatch, "/v1/device/dev-1/fcm-token", bytes.NewReader(body))
	r.Header.Set(headerHMAC, sig)
	r.Header.Set(headerDeviceID, "dev-1")
	r.Header.Set(headerNonce, "n-stale")
	r.Header.Set(headerTimestamp, stale)

	if err := srv.verifyRESTHMAC(r, body, "dev-1", defaultMockSecret); err != errStaleTimestamp {
		t.Fatalf("expected errStaleTimestamp, got %v", err)
	}
}

func TestRESTHMAC_RejectsTamperedBody(t *testing.T) {
	srv := newTestServer(t)
	signedBody := []byte(`{"fcmToken":"a"}`)
	tampered := []byte(`{"fcmToken":"b"}`)
	sig, dev, nonce, ts := signRESTRequest(t, defaultMockSecret, "dev-1", signedBody)

	r := httptest.NewRequest(http.MethodPatch, "/v1/device/dev-1/fcm-token", bytes.NewReader(tampered))
	r.Header.Set(headerHMAC, sig)
	r.Header.Set(headerDeviceID, dev)
	r.Header.Set(headerNonce, nonce)
	r.Header.Set(headerTimestamp, ts)

	if err := srv.verifyRESTHMAC(r, tampered, "dev-1", defaultMockSecret); err != errBadSignature {
		t.Fatalf("expected errBadSignature, got %v", err)
	}
}

func TestRESTHMAC_RejectsDeviceIDMismatch(t *testing.T) {
	srv := newTestServer(t)
	body := []byte(`{}`)
	sig, _, nonce, ts := signRESTRequest(t, defaultMockSecret, "dev-1", body)

	r := httptest.NewRequest(http.MethodPatch, "/v1/device/dev-1/fcm-token", bytes.NewReader(body))
	r.Header.Set(headerHMAC, sig)
	r.Header.Set(headerDeviceID, "dev-OTHER")
	r.Header.Set(headerNonce, nonce)
	r.Header.Set(headerTimestamp, ts)

	if err := srv.verifyRESTHMAC(r, body, "dev-1", defaultMockSecret); err != errDeviceIDMismatch {
		t.Fatalf("expected errDeviceIDMismatch, got %v", err)
	}
}

// TestCommandFrame_CanonicalMessageFormat pins the canonical message format
// against the worked example in COMMAND_SECURITY.md §3. If the doc and the
// code disagree on the format, this test fails — preventing silent
// drift between Go and Kotlin.
func TestCommandFrame_CanonicalMessageFormat(t *testing.T) {
	frame := commandFrame{
		TransactionID: "f7893a2-bcd0-4e12",
		DeviceID:      "uuid-nokia-c22-092831",
		Action:        "REINIT_PROJECTION",
		TimestampMs:   1748260800000,
		Nonce:         "a3f8c1d2e4b56789",
		// Params omitted — canonical for empty params is "{}".
	}
	canonical := buildCommandFrameCanonical(&frame)
	expected := "f7893a2-bcd0-4e12|uuid-nokia-c22-092831|REINIT_PROJECTION|1748260800000|a3f8c1d2e4b56789|{}"
	if string(canonical) != expected {
		t.Fatalf("canonical mismatch:\n got: %q\nwant: %q", canonical, expected)
	}
}

func TestCommandFrame_HMACRoundTrip(t *testing.T) {
	srv := newTestServer(t)
	frame := commandFrame{
		TransactionID: "tx-1",
		DeviceID:      "dev-1",
		Action:        "PING",
		TimestampMs:   time.Now().UnixMilli(),
		Nonce:         "n-rt",
	}
	canonical := buildCommandFrameCanonical(&frame)
	frame.HMAC = signCanonicalHex(defaultMockSecret, canonical)

	if err := srv.verifyCommandFrame(&frame, defaultMockSecret); err != nil {
		t.Fatalf("verifyCommandFrame: %v", err)
	}
}

func TestCommandFrame_RejectsTamperedAction(t *testing.T) {
	srv := newTestServer(t)
	frame := commandFrame{
		TransactionID: "tx-2",
		DeviceID:      "dev-1",
		Action:        "PING",
		TimestampMs:   time.Now().UnixMilli(),
		Nonce:         "n-tamper",
	}
	frame.HMAC = signCanonicalHex(defaultMockSecret, buildCommandFrameCanonical(&frame))
	frame.Action = "REBOOT" // tampered after signing

	if err := srv.verifyCommandFrame(&frame, defaultMockSecret); err != errBadSignature {
		t.Fatalf("expected errBadSignature, got %v", err)
	}
}

func TestCommandFrame_ParamsAreCanonicalized(t *testing.T) {
	srv := newTestServer(t)
	// Whitespace-significant: the canonical message contains params *as-is*.
	// Two clients that produce the same logical JSON with different
	// formatting will produce different HMACs. This is by design — the
	// canonical form per COMMAND_SECURITY.md §3 is "params is the raw JSON
	// string as-is".
	frame := commandFrame{
		TransactionID: "tx-3",
		DeviceID:      "dev-1",
		Action:        "FORCE_SPEAKER",
		TimestampMs:   time.Now().UnixMilli(),
		Nonce:         "n-params",
		Params:        []byte(`{"duration_ms":60000}`),
	}
	frame.HMAC = signCanonicalHex(defaultMockSecret, buildCommandFrameCanonical(&frame))

	if err := srv.verifyCommandFrame(&frame, defaultMockSecret); err != nil {
		t.Fatalf("expected verify success, got %v", err)
	}
}

func TestWSConnectHMAC_HappyPath(t *testing.T) {
	srv := newTestServer(t)
	srv.store.register(registerRequest{DeviceID: "dev-ws", FirebaseInstallID: "fid-ws"}, time.Now())

	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce := "n-ws-1"
	canonical := buildConnectCanonical("dev-ws", ts, nonce)
	sig := signCanonicalForTest(t, defaultMockSecret, canonical)

	r := httptest.NewRequest(http.MethodGet, "/v1/device/dev-ws/stream", nil)
	r.Header.Set(headerHMAC, sig)
	r.Header.Set(headerDeviceID, "dev-ws")
	r.Header.Set(headerNonce, nonce)
	r.Header.Set(headerTimestamp, ts)

	if err := srv.verifyWSConnectHMAC(r, "dev-ws", defaultMockSecret); err != nil {
		t.Fatalf("verifyWSConnectHMAC: %v", err)
	}
}

func TestFleetToken_HappyPath(t *testing.T) {
	srv := newTestServer(t)
	r := httptest.NewRequest(http.MethodPost, "/v1/device/register", nil)
	r.Header.Set("Authorization", "Bearer "+testFleetToken)
	if err := srv.verifyFleetToken(r); err != nil {
		t.Fatalf("verifyFleetToken: %v", err)
	}
}

func TestFleetToken_RejectsMissing(t *testing.T) {
	srv := newTestServer(t)
	r := httptest.NewRequest(http.MethodPost, "/v1/device/register", nil)
	if err := srv.verifyFleetToken(r); err != errMissingFleetToken {
		t.Fatalf("expected errMissingFleetToken, got %v", err)
	}
}

func TestFleetToken_RejectsWrong(t *testing.T) {
	srv := newTestServer(t)
	r := httptest.NewRequest(http.MethodPost, "/v1/device/register", nil)
	r.Header.Set("Authorization", "Bearer not-the-real-token")
	if err := srv.verifyFleetToken(r); err != errInvalidFleetToken {
		t.Fatalf("expected errInvalidFleetToken, got %v", err)
	}
}
