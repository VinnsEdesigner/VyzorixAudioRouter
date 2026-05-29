// Package main is the entrypoint for the Phase 1 mock-server (see ADR-0009).
//
// This binary implements just enough of the vyzorix-update-server contract for
// the Android daemon to exercise Layers 7 and 8 end-to-end. State is in-memory;
// restarting forgets every device. The real server replaces this in Phase 1.5
// with no Android code changes.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	addr := flag.String("addr", ":8080", "HTTP listen address")
	dataDir := flag.String("data", "./cmd/mockserver/testdata", "directory containing version.json and the dummy APK")
	logLevel := flag.String("log-level", "info", "log level: debug, info, warn, error")
	mockSecret := flag.String("mock-secret", defaultMockSecret, "command_secret returned to every device that registers (64 hex chars)")
	strictHMAC := flag.Bool("strict-hmac", false, "when true, reject requests whose HMAC does not validate; when false, log and accept")
	flag.Parse()

	logger := newLogger(*logLevel)
	slog.SetDefault(logger)

	absData, err := filepath.Abs(*dataDir)
	if err != nil {
		logger.Error("cannot resolve data dir", "dir", *dataDir, "err", err)
		os.Exit(1)
	}
	if err := validateDataDir(absData); err != nil {
		logger.Error("data dir invalid", "dir", absData, "err", err)
		os.Exit(1)
	}

	store := newStore(*mockSecret)
	srv := newServer(logger, store, absData, *strictHMAC)

	httpSrv := &http.Server{
		Addr:              *addr,
		Handler:           srv.routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		logger.Info("mockserver listening",
			"addr", *addr,
			"data", absData,
			"strict_hmac", *strictHMAC,
		)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received, stopping http server")
	case err := <-errCh:
		logger.Error("http server failed", "err", err)
		os.Exit(1)
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpSrv.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", "err", err)
		os.Exit(1)
	}
	store.closeAllWebSockets()
	logger.Info("mockserver stopped")
}

func newLogger(level string) *slog.Logger {
	var lvl slog.Level
	switch level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	return slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: lvl}))
}

func validateDataDir(dir string) error {
	info, err := os.Stat(dir)
	if err != nil {
		return fmt.Errorf("stat: %w", err)
	}
	if !info.IsDir() {
		return errors.New("not a directory")
	}
	if _, err := os.Stat(filepath.Join(dir, "version.json")); err != nil {
		return fmt.Errorf("version.json: %w", err)
	}
	return nil
}
