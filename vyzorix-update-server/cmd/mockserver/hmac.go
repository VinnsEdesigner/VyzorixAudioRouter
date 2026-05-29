package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

// HMAC contract — see doc/COMMAND_SECURITY.md and doc/DEVICE_REGISTRATION.md.
//
// Three distinct canonical-message schemes coexist in this server. They are
// NOT interchangeable; using the wrong one is a signature failure.
//
//   1. CommandFrame (server → device, over WSS or FCM)
//      canonical = "{transactionId}|{deviceId}|{action}|{timestampMs}|{nonce}|{params}"
//      output    = hex(HMAC-SHA256(canonical, command_secret))
//      placement = "hmac" field embedded inside the JSON CommandFrame body
//      reference = COMMAND_SECURITY.md §2 + §3
//
//   2. REST body-signed (device → server admin endpoints)
//      canonical = raw request body bytes (empty for DELETE/GET)
//      output    = hex(HMAC-SHA256(canonical, command_secret))
//      placement = X-Vyzorix-Hmac header; X-Vyzorix-Device-Id /
//                  X-Vyzorix-Timestamp / X-Vyzorix-Nonce alongside
//      reference = DEVICE_REGISTRATION.md §3.2
//
//   3. WSS handshake (device → server on upgrade)
//      canonical = "CONNECT:{deviceId}:{timestampMs}:{nonce}"
//      output    = hex(HMAC-SHA256(canonical, command_secret))
//      placement = same four headers as scheme #2
//      reference = DEVICE_REGISTRATION.md §4.1
//
// Bearer-token bootstrap (POST /v1/device/register) is NOT HMAC; it uses
// `Authorization: Bearer <fleet_registration_token>` as documented in
// DEVICE_REGISTRATION.md §3.1.

const (
	headerHMAC      = "X-Vyzorix-Hmac"
	headerDeviceID  = "X-Vyzorix-Device-Id"
	headerNonce     = "X-Vyzorix-Nonce"
	headerTimestamp = "X-Vyzorix-Timestamp"

	// hmacWindow is the per-message timestamp tolerance — COMMAND_SECURITY.md §3
	// fixes this at ±30s for both REST and CommandFrame schemes.
	hmacWindow = 30 * time.Second
)

var (
	errMissingHMACHeaders = errors.New("missing HMAC headers")
	errStaleTimestamp     = errors.New("timestamp outside ±30s window")
	errNonceReplay        = errors.New("nonce already seen")
	errBadSignature       = errors.New("signature does not match")
	errMalformedSignature = errors.New("signature header is not valid hex")
	errMalformedTimestamp = errors.New("timestamp header is not an integer (unix ms)")
	errDeviceIDMismatch   = errors.New("X-Vyzorix-Device-Id header does not match path")
	errMissingFleetToken  = errors.New("missing Authorization: Bearer <fleet_token>")
	errInvalidFleetToken  = errors.New("invalid fleet_token")
)

// readBody reads r.Body fully so the handler can JSON-unmarshal it without
// re-reading the network. The body is restored on r so a downstream
// json.NewDecoder(r.Body) still works.
func readBody(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "read_body", err.Error())
		return nil, false
	}
	r.Body = io.NopCloser(bytes.NewReader(body))
	return body, true
}

// requireRESTHMAC enforces scheme #2 (REST body-signed) for an admin endpoint.
// The deviceID is the one from the URL path; it must match the
// X-Vyzorix-Device-Id header AND identify a registered device.
//
// Returns the request body (so the handler can JSON-unmarshal it) and a
// boolean indicating whether the request should continue. If continue is
// false, an error response has already been written.
func (s *server) requireRESTHMAC(w http.ResponseWriter, r *http.Request, deviceID string) ([]byte, bool) {
	body, ok := readBody(w, r)
	if !ok {
		return nil, false
	}
	secret, found := s.store.commandSecret(deviceID)
	if !found {
		writeError(w, http.StatusNotFound, "unknown_device", deviceID)
		return nil, false
	}
	if err := s.verifyRESTHMAC(r, body, deviceID, secret); err != nil {
		writeError(w, http.StatusUnauthorized, hmacErrorCode(err), err.Error())
		return nil, false
	}
	return body, true
}

// verifyRESTHMAC parses the four headers, checks deviceId match, the timestamp
// window, nonce uniqueness, and signature equality in constant time.
func (s *server) verifyRESTHMAC(r *http.Request, body []byte, deviceID, secret string) error {
	sigHdr := r.Header.Get(headerHMAC)
	devHdr := r.Header.Get(headerDeviceID)
	nonce := r.Header.Get(headerNonce)
	tsStr := r.Header.Get(headerTimestamp)
	if sigHdr == "" || devHdr == "" || nonce == "" || tsStr == "" {
		return errMissingHMACHeaders
	}
	if devHdr != deviceID {
		return errDeviceIDMismatch
	}

	if err := s.checkTimestampAndNonce(tsStr, nonce); err != nil {
		return err
	}
	want, err := hex.DecodeString(sigHdr)
	if err != nil {
		return fmt.Errorf("%w: %v", errMalformedSignature, err)
	}
	got := signCanonical(secret, body)
	if !hmac.Equal(want, got) {
		return errBadSignature
	}
	return nil
}

// requireWSConnectHMAC enforces scheme #3 (CONNECT-style HMAC) for the WSS
// upgrade. Returns true iff the upgrade should proceed; on false, an error
// response has already been written.
func (s *server) requireWSConnectHMAC(w http.ResponseWriter, r *http.Request, deviceID string) bool {
	secret, found := s.store.commandSecret(deviceID)
	if !found {
		writeError(w, http.StatusNotFound, "unknown_device", deviceID)
		return false
	}
	if err := s.verifyWSConnectHMAC(r, deviceID, secret); err != nil {
		writeError(w, http.StatusUnauthorized, hmacErrorCode(err), err.Error())
		return false
	}
	return true
}

// verifyWSConnectHMAC validates the handshake headers and the
// "CONNECT:{deviceId}:{timestampMs}:{nonce}" canonical string per
// DEVICE_REGISTRATION.md §4.1.
func (s *server) verifyWSConnectHMAC(r *http.Request, deviceID, secret string) error {
	sigHdr := r.Header.Get(headerHMAC)
	devHdr := r.Header.Get(headerDeviceID)
	nonce := r.Header.Get(headerNonce)
	tsStr := r.Header.Get(headerTimestamp)
	if sigHdr == "" || devHdr == "" || nonce == "" || tsStr == "" {
		return errMissingHMACHeaders
	}
	if devHdr != deviceID {
		return errDeviceIDMismatch
	}
	if err := s.checkTimestampAndNonce(tsStr, nonce); err != nil {
		return err
	}
	want, err := hex.DecodeString(sigHdr)
	if err != nil {
		return fmt.Errorf("%w: %v", errMalformedSignature, err)
	}
	canonical := buildConnectCanonical(deviceID, tsStr, nonce)
	got := signCanonical(secret, canonical)
	if !hmac.Equal(want, got) {
		return errBadSignature
	}
	return nil
}

// verifyCommandFrame validates the canonical CommandFrame scheme — scheme #1
// per COMMAND_SECURITY.md §2 + §3. The HMAC is embedded as the `hmac` field
// on the frame itself; the canonical message is pipe-delimited from the
// other fields.
func (s *server) verifyCommandFrame(frame *commandFrame, secret string) error {
	if frame.TransactionID == "" || frame.DeviceID == "" || frame.Action == "" ||
		frame.Nonce == "" || frame.HMAC == "" {
		return errMissingHMACHeaders
	}
	tsStr := strconv.FormatInt(frame.TimestampMs, 10)
	if err := s.checkTimestampAndNonce(tsStr, frame.Nonce); err != nil {
		return err
	}
	want, err := hex.DecodeString(frame.HMAC)
	if err != nil {
		return fmt.Errorf("%w: %v", errMalformedSignature, err)
	}
	canonical := buildCommandFrameCanonical(frame)
	got := signCanonical(secret, canonical)
	if !hmac.Equal(want, got) {
		return errBadSignature
	}
	return nil
}

// checkTimestampAndNonce factors the timestamp-window + nonce-replay checks
// that all three schemes share.
func (s *server) checkTimestampAndNonce(tsStr, nonce string) error {
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
	return nil
}

// requireFleetToken enforces the Bearer fleet_registration_token contract for
// POST /v1/device/register — see DEVICE_REGISTRATION.md §3.1. This is the
// only endpoint that uses Bearer auth; all post-registration endpoints use
// per-device HMAC.
func (s *server) requireFleetToken(w http.ResponseWriter, r *http.Request) bool {
	if err := s.verifyFleetToken(r); err != nil {
		writeError(w, http.StatusUnauthorized, fleetTokenErrorCode(err), err.Error())
		return false
	}
	return true
}

func (s *server) verifyFleetToken(r *http.Request) error {
	auth := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(auth) < len(prefix) || auth[:len(prefix)] != prefix {
		return errMissingFleetToken
	}
	if hmacEqualString(auth[len(prefix):], s.fleetToken) {
		return nil
	}
	return errInvalidFleetToken
}

func buildCommandFrameCanonical(frame *commandFrame) []byte {
	// Order and delimiter are fixed by COMMAND_SECURITY.md §3 — do not edit.
	var buf bytes.Buffer
	buf.WriteString(frame.TransactionID)
	buf.WriteByte('|')
	buf.WriteString(frame.DeviceID)
	buf.WriteByte('|')
	buf.WriteString(frame.Action)
	buf.WriteByte('|')
	buf.WriteString(strconv.FormatInt(frame.TimestampMs, 10))
	buf.WriteByte('|')
	buf.WriteString(frame.Nonce)
	buf.WriteByte('|')
	buf.Write(paramsBytes(frame.Params))
	return buf.Bytes()
}

// paramsBytes serializes Params for inclusion in the canonical CommandFrame
// message. Per COMMAND_SECURITY.md §3 "params is the raw JSON string as-is;
// empty params = `{}`".
func paramsBytes(params json.RawMessage) []byte {
	if len(params) == 0 {
		return []byte("{}")
	}
	return []byte(params)
}

func buildConnectCanonical(deviceID, tsStr, nonce string) []byte {
	// Per DEVICE_REGISTRATION.md §4.1.
	return []byte(fmt.Sprintf("CONNECT:%s:%s:%s", deviceID, tsStr, nonce))
}

// signCanonical signs `data` with `secretHex` (a 64-char hex string).
func signCanonical(secretHex string, data []byte) []byte {
	key, err := hex.DecodeString(secretHex)
	if err != nil {
		// A misconfigured -mock-secret falls back to treating the value as
		// raw bytes so the mock never panics on a bad CLI flag.
		key = []byte(secretHex)
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(data)
	return mac.Sum(nil)
}

// signCanonicalHex is signCanonical for callers that need a hex-encoded HMAC
// (canonical CommandFrame `hmac` field, response signatures, etc.).
func signCanonicalHex(secretHex string, data []byte) string {
	return hex.EncodeToString(signCanonical(secretHex, data))
}

func hmacEqualString(a, b string) bool {
	return hmac.Equal([]byte(a), []byte(b))
}

// hmacErrorCode maps an HMAC-side error to the wire `error` code documented
// in DEVICE_REGISTRATION.md §3.2 ("invalid_hmac" / "expired_timestamp" /
// "replayed_nonce").
func hmacErrorCode(err error) string {
	switch {
	case errors.Is(err, errStaleTimestamp):
		return "expired_timestamp"
	case errors.Is(err, errNonceReplay):
		return "replayed_nonce"
	case errors.Is(err, errDeviceIDMismatch):
		return "device_id_mismatch"
	case errors.Is(err, errMissingHMACHeaders):
		return "missing_hmac"
	default:
		return "invalid_hmac"
	}
}

func fleetTokenErrorCode(err error) string {
	switch {
	case errors.Is(err, errMissingFleetToken):
		return "missing_fleet_token"
	default:
		return "invalid_fleet_token"
	}
}
