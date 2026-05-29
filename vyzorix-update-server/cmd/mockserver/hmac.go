package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

const (
	headerSignature = "X-Vyzorix-Signature"
	headerNonce     = "X-Vyzorix-Nonce"
	headerTimestamp = "X-Vyzorix-Timestamp"

	hmacWindow = 5 * time.Minute
)

var (
	errMissingHMACHeaders   = errors.New("missing HMAC headers")
	errStaleTimestamp       = errors.New("timestamp outside ±5 min window")
	errNonceReplay          = errors.New("nonce already seen")
	errBadSignature         = errors.New("signature does not match")
	errMalformedSignature   = errors.New("signature header is not valid base64")
	errMalformedTimestamp   = errors.New("timestamp header is not an integer")
)

// requireHMAC enforces the HMAC scheme documented in COMMAND_SECURITY.md.
//
// When the server is started with -strict-hmac=false (the default), a failed
// validation is logged but the request still proceeds — useful while the
// Android signing code is still being iterated on. When -strict-hmac=true,
// the validator behaves like the real server.
//
// Returns the request body (read fully so handlers can JSON-unmarshal it
// without re-reading the network) and a boolean indicating whether the
// request should continue. If continue is false, an error response has
// already been written.
func (s *server) requireHMAC(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "read_body", err.Error())
		return nil, false
	}
	r.Body = io.NopCloser(bytes.NewReader(body)) // keep body re-readable

	if err := s.verifyHMAC(r, body); err != nil {
		if s.strictHMAC {
			writeError(w, http.StatusUnauthorized, "bad_hmac", err.Error())
			return nil, false
		}
		s.log.Warn("hmac failed (non-strict mode — request still accepted)", "path", r.URL.Path, "err", err)
	}
	return body, true
}

// verifyHMAC parses the three headers, checks the timestamp window, checks
// nonce uniqueness and finally compares signatures in constant time.
func (s *server) verifyHMAC(r *http.Request, body []byte) error {
	sig := r.Header.Get(headerSignature)
	nonce := r.Header.Get(headerNonce)
	tsStr := r.Header.Get(headerTimestamp)
	if sig == "" || nonce == "" || tsStr == "" {
		return errMissingHMACHeaders
	}

	tsMillis, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil {
		return fmt.Errorf("%w: %v", errMalformedTimestamp, err)
	}
	ts := time.UnixMilli(tsMillis)
	now := time.Now()
	if ts.After(now.Add(hmacWindow)) || ts.Before(now.Add(-hmacWindow)) {
		return errStaleTimestamp
	}

	if !s.store.rememberNonce(nonce, now) {
		return errNonceReplay
	}

	want, err := base64.StdEncoding.DecodeString(sig)
	if err != nil {
		return fmt.Errorf("%w: %v", errMalformedSignature, err)
	}

	canonical := buildCanonicalMessage(r.Method, r.URL.Path, nonce, tsStr, body)
	got := signCanonical(s.store.mockSecret, canonical)
	if !hmac.Equal(want, got) {
		return errBadSignature
	}
	return nil
}

// verifyHandshakeHMAC validates the headers passed during the WSS upgrade.
// Body is empty for a GET upgrade so the canonical message is shorter. The
// deviceID is part of the path the canonical message already covers.
func (s *server) verifyHandshakeHMAC(r *http.Request, _ string) error {
	return s.verifyHMAC(r, nil)
}

// buildCanonicalMessage produces the byte string that gets HMAC-signed. The
// format is METHOD\nPATH\nNONCE\nTIMESTAMP\nBODY, LF-joined, no trailing
// newline. This matches COMMAND_SECURITY.md.
func buildCanonicalMessage(method, path, nonce, timestamp string, body []byte) []byte {
	var buf bytes.Buffer
	buf.WriteString(method)
	buf.WriteByte('\n')
	buf.WriteString(path)
	buf.WriteByte('\n')
	buf.WriteString(nonce)
	buf.WriteByte('\n')
	buf.WriteString(timestamp)
	buf.WriteByte('\n')
	buf.Write(body)
	return buf.Bytes()
}

// signCanonical signs `data` with `secretHex` (a 64-char hex string).
func signCanonical(secretHex string, data []byte) []byte {
	key, err := hex.DecodeString(secretHex)
	if err != nil {
		// Treat a malformed secret as raw bytes so the mock never crashes on
		// a misconfigured -mock-secret flag.
		key = []byte(secretHex)
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(data)
	return mac.Sum(nil)
}
