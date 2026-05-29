package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// commandFrame is the canonical CommandFrame JSON schema documented in
// COMMAND_SECURITY.md §2. Field tags MUST stay in lockstep with the spec:
//
//	transactionId, deviceId, action, timestampMs (int64 unix-ms),
//	nonce, params (raw JSON), hmac (64-char lowercase hex)
//
// The Go server signs this struct before sending it to the device; the
// device validates it on receipt against its locally-stored command_secret.
type commandFrame struct {
	TransactionID string          `json:"transactionId"`
	DeviceID      string          `json:"deviceId"`
	Action        string          `json:"action"`
	TimestampMs   int64           `json:"timestampMs"`
	Nonce         string          `json:"nonce"`
	Params        json.RawMessage `json:"params,omitempty"`
	HMAC          string          `json:"hmac"`
}

// dashboardCommandRequest is the body the dashboard POSTs to the server to
// instruct it to forward a command. The dashboard does NOT see the device's
// command_secret — the server signs on the dashboard's behalf
// (DEVICE_REGISTRATION.md §5). For the mock, "dashboard auth" is a Bearer
// token (-dashboard-token); the real server uses a session cookie.
type dashboardCommandRequest struct {
	Action string          `json:"action"`
	Params json.RawMessage `json:"params,omitempty"`
}

type dashboardCommandResponse struct {
	TransactionID string `json:"transactionId"`
	DispatchID    string `json:"dispatchId"`
	Delivery      string `json:"delivery"` // "sent" if WSS delivered, "queued" if held for FCM
	ServerTime    int64  `json:"serverTime"`
}

// handleDeviceCommand is the dashboard-facing endpoint. The dashboard
// authenticates with a session cookie / token, the server signs a
// CommandFrame on its behalf, and forwards the frame to the device.
//
// The frame embeds an HMAC computed against the canonical message:
//
//	{transactionId}|{deviceId}|{action}|{timestampMs}|{nonce}|{params}
//
// using HMAC-SHA256 with the device's per-device command_secret. Per
// COMMAND_SECURITY.md §3 and §5.
func (s *server) handleDeviceCommand(w http.ResponseWriter, r *http.Request, deviceID string) {
	if !s.requireDashboardAuth(w, r) {
		return
	}
	body, ok := readBody(w, r)
	if !ok {
		return
	}
	var req dashboardCommandRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}
	if req.Action == "" {
		writeError(w, http.StatusBadRequest, "missing_field", "action is required")
		return
	}
	secret, found := s.store.commandSecret(deviceID)
	if !found {
		writeError(w, http.StatusNotFound, "unknown_device", deviceID)
		return
	}

	now := time.Now()
	frame := commandFrame{
		TransactionID: newTransactionID(),
		DeviceID:      deviceID,
		Action:        req.Action,
		TimestampMs:   now.UnixMilli(),
		Nonce:         newNonce(),
		Params:        req.Params,
	}
	canonical := buildCommandFrameCanonical(&frame)
	frame.HMAC = signCanonicalHex(secret, canonical)

	delivered := s.store.dispatch(deviceID, frame)
	delivery := "queued"
	if delivered {
		delivery = "sent"
	}

	dispatchID := newDispatchID(now)
	s.log.Info("command dispatched",
		"deviceId", deviceID,
		"action", req.Action,
		"transactionId", frame.TransactionID,
		"dispatchId", dispatchID,
		"delivery", delivery,
	)

	writeJSON(w, http.StatusAccepted, dashboardCommandResponse{
		TransactionID: frame.TransactionID,
		DispatchID:    dispatchID,
		Delivery:      delivery,
		ServerTime:    now.UnixMilli(),
	})
}

// newTransactionID returns an opaque transaction identifier for a single
// CommandFrame. 16 random bytes hex-encoded is enough to make accidental
// collisions effectively impossible — same shape as the device-side IDs in
// COMMAND_SECURITY.md §2.
func newTransactionID() string {
	return randomHex(16, "tx")
}

// newNonce returns the 16-byte hex nonce embedded in the CommandFrame. The
// nonce cache in COMMAND_SECURITY.md §4 dedups against this.
func newNonce() string {
	return randomHex(16, "n")
}

func randomHex(n int, fallbackPrefix string) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		// /dev/urandom hiccup — fall back to a time-based ID rather than
		// crashing the server.
		return fmt.Sprintf("%s-%d", fallbackPrefix, time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}
