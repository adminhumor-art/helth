package httpapi

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

type Config struct {
	DeviceToken        string
	FamilySessionToken string
	PatientID          string
	FreshAfter         time.Duration
	Logger             *slog.Logger
	TelegramRecipients []string
}

type Server struct {
	config Config
	store  store.Store
	alerts *alerts.Engine
	mux    *http.ServeMux
}

func New(config Config, values store.Store, engine *alerts.Engine) *Server {
	if config.FreshAfter == 0 {
		config.FreshAfter = 10 * time.Minute
	}
	if config.Logger == nil {
		config.Logger = slog.Default()
	}
	s := &Server{config: config, store: values, alerts: engine, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) Handler() http.Handler {
	return s.securityHeaders(s.mux)
}

// PrimePatient gives the signal-loss detector a restart-safe baseline. If the
// database is empty, startupAt provides one stale window for the phone to send
// its first value instead of raising an alarm immediately on every deployment.
func (s *Server) PrimePatient(ctx context.Context, patientID string, startupAt time.Time) error {
	latest, err := s.store.Latest(ctx, patientID)
	if errors.Is(err, store.ErrNotFound) {
		s.alerts.SeedLatest(patientID, startupAt)
		return nil
	}
	if err != nil {
		return err
	}
	s.alerts.SeedLatest(patientID, latest.SensorTime)
	return nil
}

// CheckStaleness is called by the process scheduler independently of incoming
// measurements. This is essential: a lost phone cannot trigger its own alert.
func (s *Server) CheckStaleness(ctx context.Context, patientID string, at time.Time) {
	for _, change := range s.alerts.EvaluateStaleness(patientID, at) {
		s.persistAndNotify(ctx, change)
	}
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /healthz", s.health)
	s.mux.HandleFunc("POST /v1/device/measurements", s.ingestMeasurement)
	s.mux.HandleFunc("GET /v1/patients/{patientId}/snapshot", s.patientSnapshot)
	s.mux.HandleFunc("GET /v1/patients/{patientId}/measurements", s.listMeasurements)
	s.mux.HandleFunc("POST /v1/alerts/{alertId}/acknowledge", s.acknowledgeAlert)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) ingestMeasurement(w http.ResponseWriter, r *http.Request) {
	if !bearerMatches(r, s.config.DeviceToken) {
		writeProblem(w, http.StatusUnauthorized, "invalid device token")
		return
	}
	var value domain.Measurement
	if err := decodeJSON(w, r, &value); err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	now := time.Now().UTC()
	value.PatientID = s.config.PatientID
	value.ReceivedAt = now
	if err := value.Validate(now); err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	duplicate, err := s.store.Ingest(r.Context(), value)
	if err != nil {
		s.config.Logger.Error("store measurement", "error", err)
		writeProblem(w, http.StatusInternalServerError, "measurement could not be stored")
		return
	}
	if !duplicate {
		for _, change := range s.alerts.Evaluate(value) {
			s.persistAndNotify(r.Context(), change)
		}
		for _, change := range s.alerts.EvaluateStaleness(value.PatientID, now) {
			s.persistAndNotify(r.Context(), change)
		}
	}
	writeJSON(w, http.StatusAccepted, map[string]any{
		"accepted": true, "duplicate": duplicate, "serverTime": now,
	})
}

func (s *Server) patientSnapshot(w http.ResponseWriter, r *http.Request) {
	if !s.familyAuthorized(r) {
		writeProblem(w, http.StatusUnauthorized, "family session is required")
		return
	}
	patientID := r.PathValue("patientId")
	if patientID != s.config.PatientID {
		writeProblem(w, http.StatusNotFound, "patient not found")
		return
	}
	latest, err := s.store.Latest(r.Context(), patientID)
	if errors.Is(err, store.ErrNotFound) {
		writeJSON(w, http.StatusOK, domain.PatientSnapshot{
			PatientID: patientID, Freshness: domain.FreshnessMissing,
			OpenAlerts: s.alerts.OpenAlerts(patientID),
		})
		return
	}
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "snapshot could not be loaded")
		return
	}
	freshness := domain.FreshnessFresh
	if time.Since(latest.SensorTime) >= s.config.FreshAfter {
		freshness = domain.FreshnessStale
	}
	writeJSON(w, http.StatusOK, domain.PatientSnapshot{
		PatientID: patientID, Freshness: freshness, Latest: latest,
		OpenAlerts: s.alerts.OpenAlerts(patientID),
	})
}

func (s *Server) listMeasurements(w http.ResponseWriter, r *http.Request) {
	if !s.familyAuthorized(r) {
		writeProblem(w, http.StatusUnauthorized, "family session is required")
		return
	}
	patientID := r.PathValue("patientId")
	if patientID != s.config.PatientID {
		writeProblem(w, http.StatusNotFound, "patient not found")
		return
	}
	from, err := time.Parse(time.RFC3339, r.URL.Query().Get("from"))
	if err != nil {
		writeProblem(w, http.StatusBadRequest, "from must be RFC3339")
		return
	}
	to, err := time.Parse(time.RFC3339, r.URL.Query().Get("to"))
	if err != nil || to.Before(from) || to.Sub(from) > 90*24*time.Hour {
		writeProblem(w, http.StatusBadRequest, "to must be RFC3339, after from, and within 90 days")
		return
	}
	values, err := s.store.List(r.Context(), patientID, from, to)
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "measurements could not be loaded")
		return
	}
	writeJSON(w, http.StatusOK, values)
}

func (s *Server) acknowledgeAlert(w http.ResponseWriter, r *http.Request) {
	if !s.familyAuthorized(r) {
		writeProblem(w, http.StatusUnauthorized, "family session is required")
		return
	}
	if err := s.store.AcknowledgeAlert(r.Context(), r.PathValue("alertId"), time.Now().UTC()); errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusNotFound, "alert not found")
		return
	} else if err != nil {
		writeProblem(w, http.StatusInternalServerError, "alert could not be acknowledged")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) persistAndNotify(ctx context.Context, change alerts.Change) {
	var recipients []string
	if change.Type == alerts.Opened {
		recipients = s.config.TelegramRecipients
	}
	if err := s.store.SaveAlert(ctx, change.Alert, recipients); err != nil {
		s.config.Logger.Error("save alert", "error", err, "kind", change.Alert.Kind)
	}
}

func (s *Server) familyAuthorized(r *http.Request) bool {
	cookie, err := r.Cookie("family_session")
	if err == nil && secretMatches(cookie.Value, s.config.FamilySessionToken) {
		return true
	}
	return bearerMatches(r, s.config.FamilySessionToken)
}

func bearerMatches(r *http.Request, expected string) bool {
	value := strings.TrimSpace(r.Header.Get("Authorization"))
	if !strings.HasPrefix(value, "Bearer ") {
		return false
	}
	return secretMatches(strings.TrimSpace(strings.TrimPrefix(value, "Bearer ")), expected)
}

func secretMatches(actual, expected string) bool {
	if actual == "" || expected == "" || len(actual) != len(expected) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(actual), []byte(expected)) == 1
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dst); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("request must contain exactly one JSON object")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeProblem(w http.ResponseWriter, status int, detail string) {
	writeJSON(w, status, map[string]any{
		"type": "about:blank", "title": http.StatusText(status), "status": status, "detail": detail,
	})
}

func (s *Server) securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
