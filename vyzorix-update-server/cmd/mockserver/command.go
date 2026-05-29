package main

import (
	"encoding/json"
	"net/http"
	"time"
)

// commandRequest matches the body schema documented in COMMAND_SECURITY.md
// and DEVICE_REGISTRATION.md §5 (command issuance flow). All fields except
// args are required.
type commandRequest struct {
	Command   string          `json:"command"`
	Args      json.RawMessage `json:"args,omitempty"`
	Nonce     string          `json:"nonce"`
	Timestamp int64           `json:"timestamp"`
	Signature string          `json:"signature,omitempty"` // signed by server in real deployment
}

type commandResponse struct {
	DispatchID string `json:"dispatchId"`
	Delivery   string `json:"delivery"` // "sent" if WSS delivered, "queued" if held for next FCM wake
	ServerTime int64  `json:"serverTime"`
}

func (s *server) handleDeviceCommand(w http.ResponseWriter, r *http.Request, deviceID string) {
	body, ok := s.requireHMAC(w, r)
	if !ok {
		return
	}
	var req commandRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}
	if req.Command == "" {
		writeError(w, http.StatusBadRequest, "missing_field", "command is required")
		return
	}
	if _, found := s.store.get(deviceID); !found {
		writeError(w, http.StatusNotFound, "unknown_device", deviceID)
		return
	}

	now := time.Now()
	dispatchID := newDispatchID(now)

	// Try to deliver over an open WSS first. If the device is offline, queue
	// the command in memory; the real server would also fire an FCM wake here.
	frame := commandFrame{
		Type:       "command",
		DispatchID: dispatchID,
		Command:    req.Command,
		Args:       req.Args,
		Nonce:      req.Nonce,
		Timestamp:  req.Timestamp,
		Signature:  req.Signature,
	}
	delivered := s.store.dispatch(deviceID, frame)
	delivery := "queued"
	if delivered {
		delivery = "sent"
	}

	s.log.Info("command dispatched",
		"deviceId", deviceID,
		"command", req.Command,
		"dispatchId", dispatchID,
		"delivery", delivery,
	)

	writeJSON(w, http.StatusAccepted, commandResponse{
		DispatchID: dispatchID,
		Delivery:   delivery,
		ServerTime: now.UnixMilli(),
	})
}

// commandFrame is the wire-format pushed to the device over WSS.
type commandFrame struct {
	Type       string          `json:"type"` // always "command"
	DispatchID string          `json:"dispatchId"`
	Command    string          `json:"command"`
	Args       json.RawMessage `json:"args,omitempty"`
	Nonce      string          `json:"nonce"`
	Timestamp  int64           `json:"timestamp"`
	Signature  string          `json:"signature,omitempty"`
}
