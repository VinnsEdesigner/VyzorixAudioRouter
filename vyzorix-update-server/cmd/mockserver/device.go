package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"
)

// registerRequest matches the body schema documented in DEVICE_REGISTRATION.md
// §3.1 (POST /v1/device/register).
type registerRequest struct {
	DeviceID          string `json:"deviceId"`
	FirebaseInstallID string `json:"firebaseInstallId"`
	FCMToken          string `json:"fcmToken"`
	AppVersion        string `json:"appVersion"`
	DeviceClass       string `json:"deviceClass"`
}

type registerResponse struct {
	DeviceID      string `json:"deviceId"`
	CommandSecret string `json:"commandSecret"` // returned exactly once — DEVICE_REGISTRATION.md §3.1
	RegisteredAt  int64  `json:"registeredAt"`
	ServerTime    int64  `json:"serverTime"`
}

func (s *server) handleDeviceRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.requireFleetToken(w, r) {
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
			writeError(w, http.StatusConflict, "already_registered", "deviceId belongs to a different firebaseInstallId")
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
	writeJSON(w, http.StatusCreated, registerResponse{
		DeviceID:      dev.DeviceID,
		CommandSecret: dev.CommandSecret,
		RegisteredAt:  dev.RegisteredAt.UnixMilli(),
		ServerTime:    now.UnixMilli(),
	})
}

// fcmTokenRequest is the body of PATCH /v1/device/{id}/fcm-token. The HMAC
// is in the X-Vyzorix-Hmac header (computed over the raw body bytes);
// nonce / timestamp / deviceId are in the matching headers per
// DEVICE_REGISTRATION.md §3.2.
type fcmTokenRequest struct {
	FCMToken string `json:"fcmToken"`
}

type fcmTokenResponse struct {
	DeviceID  string `json:"deviceId"`
	FCMToken  string `json:"fcmToken"`
	UpdatedAt int64  `json:"updatedAt"`
}

func (s *server) handleDeviceFCMToken(w http.ResponseWriter, r *http.Request, deviceID string) {
	body, ok := s.requireRESTHMAC(w, r, deviceID)
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
	now := time.Now()
	if !s.store.updateFCMToken(deviceID, req.FCMToken, now) {
		writeError(w, http.StatusNotFound, "device_not_found", deviceID)
		return
	}
	s.log.Info("fcm token updated", "deviceId", deviceID)
	writeJSON(w, http.StatusOK, fcmTokenResponse{
		DeviceID:  deviceID,
		FCMToken:  req.FCMToken,
		UpdatedAt: now.UnixMilli(),
	})
}

type statusResponse struct {
	DeviceID    string `json:"deviceId"`
	State       string `json:"state"`
	IsOnline    bool   `json:"isOnline"`
	LastSeen    int64  `json:"lastSeen"`
	AppVersion  string `json:"appVersion"`
	DeviceClass string `json:"deviceClass"`
	// commandSecret is INTENTIONALLY OMITTED — DEVICE_REGISTRATION.md §3.3.
}

func (s *server) handleDeviceStatus(w http.ResponseWriter, r *http.Request, deviceID string) {
	if !s.requireDashboardAuth(w, r) {
		return
	}
	dev, ok := s.store.get(deviceID)
	if !ok {
		writeError(w, http.StatusNotFound, "device_not_found", deviceID)
		return
	}
	online := s.store.isOnline(deviceID)
	state := "REGISTERED"
	if online {
		state = "ONLINE"
	} else if !dev.LastSeen.IsZero() {
		state = "OFFLINE"
	}
	writeJSON(w, http.StatusOK, statusResponse{
		DeviceID:    dev.DeviceID,
		State:       state,
		IsOnline:    online,
		LastSeen:    dev.LastSeen.UnixMilli(),
		AppVersion:  dev.AppVersion,
		DeviceClass: dev.DeviceClass,
	})
}

func (s *server) handleDeviceDelete(w http.ResponseWriter, r *http.Request, deviceID string) {
	// DEVICE_REGISTRATION.md §3.4: device-initiated deregistration uses
	// HMAC headers. Dashboard-initiated would use the cookie; the mock
	// accepts dashboard token as an alternative.
	if s.dashboardTokenPresent(r) {
		if !s.requireDashboardAuth(w, r) {
			return
		}
	} else {
		if _, ok := s.requireRESTHMAC(w, r, deviceID); !ok {
			return
		}
	}
	closed := s.store.delete(deviceID)
	s.log.Info("device deregistered", "deviceId", deviceID, "websocketClosed", closed)
	w.WriteHeader(http.StatusNoContent)
}
