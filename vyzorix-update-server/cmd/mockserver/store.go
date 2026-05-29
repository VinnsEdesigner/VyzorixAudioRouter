package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// defaultMockSecret is the deterministic command_secret stored on every
// device record by default. Matches the CI bypass mode in
// doc/CI_CD_WORKFLOWS.md so the Android side can opt in to bypass behaviour
// without environment hopping. Mock-grade: the real server generates a
// fresh per-device secret at registration (DEVICE_REGISTRATION.md §6).
const defaultMockSecret = "0000000000000000000000000000000000000000000000000000000000000000"

// defaultFleetToken is the deterministic fleet_registration_token enforced on
// POST /v1/device/register. Real APKs would bake a fleet token in at build
// time per DEVICE_REGISTRATION.md §3.1; the mock uses a fixed value so tests
// and the device-side bootstrap can share the same string.
const defaultFleetToken = "mock-fleet-registration-token"

var errHijackAttempt = errors.New("device_id already registered to a different firebaseInstallId")

type device struct {
	DeviceID          string
	FirebaseInstallID string
	FCMToken          string
	AppVersion        string
	DeviceClass       string
	CommandSecret     string
	RegisteredAt      time.Time
	LastSeen          time.Time
}

// store is the entire in-memory state of the mock server. Fields are guarded
// by mu. There is no on-disk persistence and no eviction — restarting the
// binary forgets every device.
type store struct {
	mu         sync.Mutex
	mockSecret string
	devices    map[string]*device
	sockets    map[string]*wsRegistration
	nonces     map[string]time.Time
}

func newStore(mockSecret string) *store {
	return &store{
		mockSecret: mockSecret,
		devices:    make(map[string]*device),
		sockets:    make(map[string]*wsRegistration),
		nonces:     make(map[string]time.Time),
	}
}

// register implements the idempotency rules from DEVICE_REGISTRATION.md §3.
// A second POST with the same deviceId AND same firebaseInstallId is treated
// as an idempotent retry. A second POST with the same deviceId but a different
// firebaseInstallId is rejected (anti-hijack).
func (s *store) register(req registerRequest, now time.Time) (*device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if existing, ok := s.devices[req.DeviceID]; ok {
		if existing.FirebaseInstallID != req.FirebaseInstallID {
			return nil, errHijackAttempt
		}
		existing.FCMToken = req.FCMToken
		existing.AppVersion = req.AppVersion
		existing.DeviceClass = req.DeviceClass
		existing.LastSeen = now
		return existing, nil
	}
	dev := &device{
		DeviceID:          req.DeviceID,
		FirebaseInstallID: req.FirebaseInstallID,
		FCMToken:          req.FCMToken,
		AppVersion:        req.AppVersion,
		DeviceClass:       req.DeviceClass,
		CommandSecret:     s.mockSecret,
		RegisteredAt:      now,
		LastSeen:          now,
	}
	s.devices[req.DeviceID] = dev
	return dev, nil
}

// commandSecret returns the per-device command_secret used to validate
// signed messages. Mock implementation: every device shares -mock-secret;
// the real server keeps a distinct secret per device in a file-backed
// secret store (DEVICE_REGISTRATION.md §6.1).
func (s *store) commandSecret(deviceID string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[deviceID]
	if !ok {
		return "", false
	}
	return d.CommandSecret, true
}

func (s *store) get(deviceID string) (*device, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[deviceID]
	return d, ok
}

func (s *store) updateFCMToken(deviceID, token string, now time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[deviceID]
	if !ok {
		return false
	}
	d.FCMToken = token
	d.LastSeen = now
	return true
}

func (s *store) touch(deviceID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if d, ok := s.devices[deviceID]; ok {
		d.LastSeen = time.Now()
	}
}

func (s *store) delete(deviceID string) bool {
	s.mu.Lock()
	reg, hadSocket := s.sockets[deviceID]
	delete(s.sockets, deviceID)
	delete(s.devices, deviceID)
	s.mu.Unlock()
	if hadSocket && reg != nil {
		reg.closeWithCode(websocket.CloseNormalClosure)
	}
	return hadSocket
}

// isOnline reports whether a WSS connection is currently registered for the
// given device.
func (s *store) isOnline(deviceID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.sockets[deviceID]
	return ok
}

// dispatch attempts to push a command frame over an open WSS. Returns true
// when delivered, false when no socket is currently registered.
func (s *store) dispatch(deviceID string, frame commandFrame) bool {
	s.mu.Lock()
	reg, ok := s.sockets[deviceID]
	s.mu.Unlock()
	if !ok || reg == nil {
		return false
	}
	select {
	case reg.outbound <- frame:
		return true
	default:
		// Buffer full — treat as if the device is unreachable so the caller
		// reports "queued" rather than crashing.
		return false
	}
}

// attachWebSocket registers a freshly-upgraded connection. If a connection
// already exists for the same device, the previous one is closed with
// CloseGoingAway (matches the real server's last-write-wins policy).
func (s *store) attachWebSocket(deviceID string, conn *websocket.Conn) *wsRegistration {
	reg := &wsRegistration{
		store:    s,
		deviceID: deviceID,
		conn:     conn,
		outbound: make(chan commandFrame, 16),
		closed:   make(chan struct{}),
	}
	s.mu.Lock()
	if prev, ok := s.sockets[deviceID]; ok {
		s.mu.Unlock()
		prev.closeWithCode(websocket.CloseGoingAway)
		s.mu.Lock()
	}
	s.sockets[deviceID] = reg
	s.mu.Unlock()
	return reg
}

// closeAllWebSockets is called on server shutdown to make in-flight clients
// notice the server is going away.
func (s *store) closeAllWebSockets() {
	s.mu.Lock()
	regs := make([]*wsRegistration, 0, len(s.sockets))
	for _, r := range s.sockets {
		regs = append(regs, r)
	}
	s.sockets = make(map[string]*wsRegistration)
	s.mu.Unlock()
	for _, r := range regs {
		r.closeWithCode(websocket.CloseGoingAway)
	}
}

// rememberNonce records a nonce as seen at `now`. Returns false if the nonce
// is already in the cache (replay attempt). TTL = 5 minutes per
// COMMAND_SECURITY.md §4 (timestamp window is ±30s; nonce cache keeps an
// extra safety margin).
//
// Mock-grade: this is a flat map with opportunistic GC, no LRU cap, no
// distributed dedup. The canonical implementation per COMMAND_SECURITY.md
// §4 is a thread-safe LinkedHashMap with a 200-entry cap, LRU eviction, and
// (in production) shared state across server replicas. See the mock-server
// README for the deviation rationale.
func (s *store) rememberNonce(nonce string, now time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, seen := s.nonces[nonce]; seen {
		return false
	}
	s.nonces[nonce] = now
	cutoff := now.Add(-nonceCacheTTL)
	for k, t := range s.nonces {
		if t.Before(cutoff) {
			delete(s.nonces, k)
		}
	}
	return true
}

const nonceCacheTTL = 5 * time.Minute

// wsRegistration is the device-side view of a registered socket.
type wsRegistration struct {
	store    *store
	deviceID string
	conn     *websocket.Conn
	outbound chan commandFrame
	closed   chan struct{}
	closeMu  sync.Once
}

func (r *wsRegistration) detach() {
	r.store.mu.Lock()
	if r.store.sockets[r.deviceID] == r {
		delete(r.store.sockets, r.deviceID)
	}
	r.store.mu.Unlock()
	r.closeMu.Do(func() {
		close(r.closed)
		_ = r.conn.Close()
	})
}

func (r *wsRegistration) closeWithCode(code int) {
	r.closeMu.Do(func() {
		_ = r.conn.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(code, ""),
			time.Now().Add(2*time.Second),
		)
		close(r.closed)
		_ = r.conn.Close()
	})
}

// newDispatchID returns an opaque dispatch identifier. 16 random bytes hex-
// encoded is enough to make accidental collisions effectively impossible.
func newDispatchID(now time.Time) string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// Fall back to a time-based ID so the server never panics on a
		// transient /dev/urandom hiccup.
		return fmt.Sprintf("ts-%d", now.UnixNano())
	}
	return hex.EncodeToString(b[:])
}
