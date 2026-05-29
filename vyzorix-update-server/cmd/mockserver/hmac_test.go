package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
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

func newTestServer(t *testing.T, strict bool) *server {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelInfo}))
	wd, _ := os.Getwd()
	st := newStore(defaultMockSecret)
	return newServer(logger, st, wd+"/testdata", strict)
}

func signRequest(t *testing.T, secretHex, method, path, body string) (sig, nonce, ts string) {
	t.Helper()
	nonce = "test-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	ts = strconv.FormatInt(time.Now().UnixMilli(), 10)
	canonical := buildCanonicalMessage(method, path, nonce, ts, []byte(body))
	key, err := hex.DecodeString(secretHex)
	if err != nil {
		t.Fatalf("decode secret: %v", err)
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(canonical)
	sig = base64.StdEncoding.EncodeToString(mac.Sum(nil))
	return
}

func TestVerifyHMAC_HappyPath(t *testing.T) {
	srv := newTestServer(t, true)
	body := `{"command":"PING","args":{},"nonce":"n","timestamp":1}`
	sig, nonce, ts := signRequest(t, defaultMockSecret, http.MethodPost, "/v1/device/dev-1/command", body)

	r := httptest.NewRequest(http.MethodPost, "/v1/device/dev-1/command", bytes.NewReader([]byte(body)))
	r.Header.Set(headerSignature, sig)
	r.Header.Set(headerNonce, nonce)
	r.Header.Set(headerTimestamp, ts)

	if err := srv.verifyHMAC(r, []byte(body)); err != nil {
		t.Fatalf("verifyHMAC: %v", err)
	}
}

func TestVerifyHMAC_RejectsReplay(t *testing.T) {
	srv := newTestServer(t, true)
	body := `{"command":"PING"}`
	sig, nonce, ts := signRequest(t, defaultMockSecret, http.MethodPost, "/v1/device/dev-1/command", body)

	mkReq := func() *http.Request {
		r := httptest.NewRequest(http.MethodPost, "/v1/device/dev-1/command", bytes.NewReader([]byte(body)))
		r.Header.Set(headerSignature, sig)
		r.Header.Set(headerNonce, nonce)
		r.Header.Set(headerTimestamp, ts)
		return r
	}
	if err := srv.verifyHMAC(mkReq(), []byte(body)); err != nil {
		t.Fatalf("first call failed: %v", err)
	}
	if err := srv.verifyHMAC(mkReq(), []byte(body)); err != errNonceReplay {
		t.Fatalf("expected errNonceReplay, got %v", err)
	}
}

func TestVerifyHMAC_RejectsStaleTimestamp(t *testing.T) {
	srv := newTestServer(t, true)
	body := `{}`
	stale := strconv.FormatInt(time.Now().Add(-10*time.Minute).UnixMilli(), 10)
	canonical := buildCanonicalMessage(http.MethodPost, "/v1/device/dev-1/command", "n", stale, []byte(body))
	mac := hmac.New(sha256.New, mustHex(defaultMockSecret))
	mac.Write(canonical)
	sig := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	r := httptest.NewRequest(http.MethodPost, "/v1/device/dev-1/command", bytes.NewReader([]byte(body)))
	r.Header.Set(headerSignature, sig)
	r.Header.Set(headerNonce, "n")
	r.Header.Set(headerTimestamp, stale)

	if err := srv.verifyHMAC(r, []byte(body)); err != errStaleTimestamp {
		t.Fatalf("expected errStaleTimestamp, got %v", err)
	}
}

func TestVerifyHMAC_RejectsTamperedBody(t *testing.T) {
	srv := newTestServer(t, true)
	signedBody := `{"command":"PING"}`
	tamperedBody := `{"command":"REBOOT"}`
	sig, nonce, ts := signRequest(t, defaultMockSecret, http.MethodPost, "/v1/device/dev-1/command", signedBody)

	r := httptest.NewRequest(http.MethodPost, "/v1/device/dev-1/command", bytes.NewReader([]byte(tamperedBody)))
	r.Header.Set(headerSignature, sig)
	r.Header.Set(headerNonce, nonce)
	r.Header.Set(headerTimestamp, ts)

	if err := srv.verifyHMAC(r, []byte(tamperedBody)); err != errBadSignature {
		t.Fatalf("expected errBadSignature, got %v", err)
	}
}

func mustHex(s string) []byte {
	b, _ := hex.DecodeString(s)
	return b
}
