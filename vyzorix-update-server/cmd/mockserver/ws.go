package main

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
)

// upgrader is permissive in the mock — anyone can connect from any origin.
// The real server tightens this per DEVICE_REGISTRATION.md §4.
var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(r *http.Request) bool { return true },
}

const (
	wsPingInterval = 30 * time.Second
	wsReadTimeout  = 90 * time.Second // > 2 missed pings before close
	wsWriteTimeout = 10 * time.Second
)

// handleDeviceStream upgrades to a WebSocket connection and proxies frames
// between the device and the in-memory dispatch queue.
//
// Frame shape (device → server): { "type": "telemetry"|"ack", ... }
// Frame shape (server → device): { "type": "command", ... }
//
// Real server validates an HMAC handshake header before upgrade; the mock
// is more permissive but logs anything that looks malformed.
func (s *server) handleDeviceStream(w http.ResponseWriter, r *http.Request, deviceID string) {
	if _, found := s.store.get(deviceID); !found {
		writeError(w, http.StatusNotFound, "unknown_device", deviceID)
		return
	}

	if s.strictHMAC {
		if err := s.verifyHandshakeHMAC(r, deviceID); err != nil {
			s.log.Warn("ws handshake hmac rejected", "deviceId", deviceID, "err", err)
			writeError(w, http.StatusUnauthorized, "bad_hmac", err.Error())
			return
		}
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.log.Warn("ws upgrade failed", "deviceId", deviceID, "err", err)
		return
	}

	s.log.Info("ws connected", "deviceId", deviceID, "remote", r.RemoteAddr)
	defer s.log.Info("ws disconnected", "deviceId", deviceID)

	// Register this conn with the store so that REST command dispatch can
	// push frames to it. The store closes the conn on shutdown / DELETE.
	registration := s.store.attachWebSocket(deviceID, conn)
	defer registration.detach()

	// Read pump.
	stop := make(chan struct{})
	go func() {
		defer close(stop)
		conn.SetReadLimit(1 << 20) // 1 MiB
		_ = conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
		conn.SetPongHandler(func(string) error {
			return conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
		})
		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
					s.log.Warn("ws read closed", "deviceId", deviceID, "err", err)
				}
				return
			}
			s.handleClientFrame(deviceID, data)
		}
	}()

	// Write pump: ping ticker + outbound frames from the store.
	ping := time.NewTicker(wsPingInterval)
	defer ping.Stop()

	for {
		select {
		case <-stop:
			return
		case <-registration.closed:
			return
		case frame, ok := <-registration.outbound:
			if !ok {
				return
			}
			if err := writeWS(conn, frame); err != nil {
				s.log.Warn("ws write failed", "deviceId", deviceID, "err", err)
				return
			}
		case <-ping.C:
			_ = conn.SetWriteDeadline(time.Now().Add(wsWriteTimeout))
			if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				s.log.Warn("ws ping failed", "deviceId", deviceID, "err", err)
				return
			}
		}
	}
}

func writeWS(conn *websocket.Conn, frame commandFrame) error {
	if err := conn.SetWriteDeadline(time.Now().Add(wsWriteTimeout)); err != nil {
		return err
	}
	return conn.WriteJSON(frame)
}

// handleClientFrame inspects the JSON envelope the device just sent. The mock
// does not act on telemetry beyond logging it — Phase 2 dashboard work owns
// real telemetry storage.
func (s *server) handleClientFrame(deviceID string, raw []byte) {
	var env struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		s.log.Warn("ws frame undecodable", "deviceId", deviceID, "err", err)
		return
	}
	switch env.Type {
	case "telemetry":
		s.log.Debug("telemetry frame", "deviceId", deviceID, "bytes", len(raw))
		s.store.touch(deviceID)
	case "ack":
		s.log.Debug("ack frame", "deviceId", deviceID, "bytes", len(raw))
	default:
		s.log.Debug("ws frame", "deviceId", deviceID, "type", env.Type, "bytes", len(raw))
	}
}
