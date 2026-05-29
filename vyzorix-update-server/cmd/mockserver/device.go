package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"
)

// registerRequest matches the body schema documented in DEVICE_REGISTRATION.md
// §3 (POST /v1/device/register).
type registerRequest struct {
	DeviceID           string `json:"deviceId"`
	FirebaseInstallID  string `json:"firebaseInstallId"`
	FCMToken           string `json:"fcmToken"`
	AppVersion         string `json:"appVersion"`
	DeviceClass        string `json:"deviceClass"`
}

type registerResponse struct {
	DeviceID      string `json:"deviceId"`
	CommandSecret string `json:"commandSecret"` // returned exactly once (DEVICE_REGISTRATION.md §3)
	RegisteredAt  int64  `json:"registeredAt"`
	ServerTime    int64  `json:"serverTime"`
}

func (s *server) handleDeviceRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req registerRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}
	if req.DeviceID == "" || req.FirebaseInstallID == "" {
		writeError(w, http.StatusBadRequest, "missing_field", "deviceId and firebaseInstallId are required")
		return
	}

	now := time.Now()
	dev, err := s.store.register(req, now)
	if err != nil {
		if errors.Is(err, errHijackAttempt) {
			writeError(w, http.StatusConflict, "device_id_in_use", "deviceId belongs to a different firebaseInstallId")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal", err.Error())
		return
	}

	s.log.Info("device registered",
		"deviceId", dev.DeviceID,
		"firebaseInstallId", dev.FirebaseInstallID,
		"appVersion", dev.AppVersion,
		"deviceClass", dev.DeviceClass,
	)
	writeJSON(w, http.StatusOK, registerResponse{
		DeviceID:      dev.DeviceID,
		CommandSecret: s.store.mockSecret,
		RegisteredAt:  dev.RegisteredAt.UnixMilli(),
		ServerTime:    now.UnixMilli(),
	})
}

type fcmTokenRequest struct {
	FCMToken  string `json:"fcmToken"`
	Nonce     string `json:"nonce"`
	Timestamp int64  `json:"timestamp"`
}

func (s *server) handleDeviceFCMToken(w http.ResponseWriter, r *http.Request, deviceID string) {
	body, ok := s.requireHMAC(w, r)
	if !ok {
		return
	}
	var req fcmTokenRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}
	if req.FCMToken == "" {
		writeError(w, http.StatusBadRequest, "missing_field", "fcmToken is required")
		return
	}
	if !s.store.updateFCMToken(deviceID, req.FCMToken) {
		writeError(w, http.StatusNotFound, "unknown_device", deviceID)
		return
	}
	s.log.Info("fcm token updated", "deviceId", deviceID)
	w.WriteHeader(http.StatusNoContent)
}

type statusResponse struct {
	DeviceID    string `json:"deviceId"`
	Online      bool   `json:"online"`
	LastSeen    int64  `json:"lastSeen"`
	AppVersion  string `json:"appVersion"`
	DeviceClass string `json:"deviceClass"`
	// commandSecret is INTENTIONALLY OMITTED — see DEVICE_REGISTRATION.md §3.
}

func (s *server) handleDeviceStatus(w http.ResponseWriter, r *http.Request, deviceID string) {
	dev, ok := s.store.get(deviceID)
	if !ok {
		writeError(w, http.StatusNotFound, "unknown_device", deviceID)
		return
	}
	writeJSON(w, http.StatusOK, statusResponse{
		DeviceID:    dev.DeviceID,
		Online:      s.store.isOnline(deviceID),
		LastSeen:    dev.LastSeen.UnixMilli(),
		AppVersion:  dev.AppVersion,
		DeviceClass: dev.DeviceClass,
	})
}

func (s *server) handleDeviceDelete(w http.ResponseWriter, r *http.Request, deviceID string) {
	if _, ok := s.requireHMAC(w, r); !ok {
		return
	}
	closed := s.store.delete(deviceID)
	s.log.Info("device deregistered", "deviceId", deviceID, "websocketClosed", closed)
	w.WriteHeader(http.StatusNoContent)
}
