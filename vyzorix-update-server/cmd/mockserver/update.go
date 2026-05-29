package main

import (
	"net/http"
	"path/filepath"
	"strings"
)

// handleVersion serves testdata/version.json verbatim. The mock never edits the
// file at runtime — change the file on disk and restart the server to bump the
// version that the device sees.
func (s *server) handleVersion(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, filepath.Join(s.dataDir, "version.json"))
}

// handleAPK serves a file from the data dir. Used by the device's
// UpdateDownloader for both HEAD (pre-download size check) and GET (resumable
// download with Range). http.ServeFile handles Range natively.
func (s *server) handleAPK(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	name := strings.TrimPrefix(r.URL.Path, "/api/v1/apk/")
	if name == "" || strings.ContainsAny(name, "/\\") {
		http.Error(w, "invalid apk name", http.StatusBadRequest)
		return
	}
	apkPath := filepath.Join(s.dataDir, name)
	// Refuse path-escape attempts even though filepath.Join is safe — keeps
	// the contract clear.
	abs, err := filepath.Abs(apkPath)
	if err != nil || !strings.HasPrefix(abs, s.dataDir) {
		http.Error(w, "invalid apk path", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, apkPath)
}
