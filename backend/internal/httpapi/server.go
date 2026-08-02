package httpapi

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/deviceprovisioning"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

type Config struct {
	FreshAfter       time.Duration
	Logger           *slog.Logger
	FamilyWebOrigins []string
	FamilySessionTTL time.Duration
	Random           io.Reader
	DeviceAPIOrigin  string
}

type Server struct {
	config  Config
	store   store.Store
	alerts  *alerts.Engine
	mux     *http.ServeMux
	origins map[string]struct{}
}

const (
	familySessionCookieName = "family_session"
	defaultFamilySessionTTL = 12 * time.Hour
	minimumFamilySessionTTL = time.Minute
	maximumFamilySessionTTL = 24 * time.Hour
)

func New(config Config, values store.Store, engine *alerts.Engine) *Server {
	if config.FreshAfter == 0 {
		config.FreshAfter = 10 * time.Minute
	}
	if config.Logger == nil {
		config.Logger = slog.Default()
	}
	if config.FamilySessionTTL <= 0 {
		config.FamilySessionTTL = defaultFamilySessionTTL
	}
	if config.FamilySessionTTL < minimumFamilySessionTTL {
		config.FamilySessionTTL = minimumFamilySessionTTL
	}
	if config.FamilySessionTTL > maximumFamilySessionTTL {
		config.FamilySessionTTL = maximumFamilySessionTTL
	}
	if config.Random == nil {
		config.Random = rand.Reader
	}
	origins := make(map[string]struct{}, len(config.FamilyWebOrigins))
	for _, origin := range config.FamilyWebOrigins {
		if origin = strings.TrimSpace(origin); origin != "" {
			origins[origin] = struct{}{}
		}
	}
	s := &Server{
		config: config, store: values, alerts: engine, mux: http.NewServeMux(), origins: origins,
	}
	s.routes()
	return s
}

func (s *Server) Handler() http.Handler {
	return s.securityHeaders(s.mux)
}

// CheckStaleness is called by the process scheduler independently of incoming
// measurements. This is essential: a lost phone cannot trigger its own alert.
func (s *Server) CheckStaleness(ctx context.Context, patientID string, at time.Time) {
	recipients, err := s.store.TelegramRecipients(ctx, patientID)
	if err != nil {
		s.config.Logger.Error("load alert recipients", "error", err, "patientId", patientID)
		return
	}
	if err := s.store.ProcessStaleness(
		ctx,
		patientID,
		at,
		recipients,
		s.alerts.PlanStaleness,
	); err != nil {
		s.config.Logger.Error("evaluate signal freshness", "error", err, "patientId", patientID)
	}
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /healthz", s.health)
	s.mux.HandleFunc("POST /v1/device/measurements", s.ingestMeasurement)
	s.mux.HandleFunc("POST /v1/device/provision", s.provisionDevice)
	s.mux.HandleFunc("POST /v1/family/session", s.issueFamilySession)
	s.mux.HandleFunc("GET /v1/patients/{patientId}/snapshot", s.patientSnapshot)
	s.mux.HandleFunc("GET /v1/patients/{patientId}/measurements", s.listMeasurements)
	s.mux.HandleFunc("POST /v1/alerts/{alertId}/acknowledge", s.acknowledgeAlert)
}

func (s *Server) provisionDevice(w http.ResponseWriter, r *http.Request) {
	var input deviceProvisionInput
	if err := decodeJSON(w, r, &input); err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	if !deviceprovisioning.ValidActivationCode(input.ActivationCode) {
		writeProblem(w, http.StatusBadRequest, "activationCode has an invalid format")
		return
	}
	if !domain.IsUUID(input.DeviceID) {
		writeProblem(w, http.StatusBadRequest, "deviceId must be a UUID")
		return
	}
	if !deviceprovisioning.ValidDeviceNonce(input.DeviceNonce) {
		writeProblem(w, http.StatusBadRequest, "deviceNonce must be a canonical 256-bit base64url value")
		return
	}
	deviceToken, err := deviceprovisioning.GenerateOpaqueToken(s.config.Random)
	if err != nil {
		s.config.Logger.Error("generate device credential", "error", err)
		writeProblem(w, http.StatusInternalServerError, "device credential could not be issued")
		return
	}
	access, err := s.store.ConsumeDeviceActivation(r.Context(), store.DeviceActivationConsume{
		CodeHash: store.HashAccessToken(input.ActivationCode), DeviceID: strings.ToLower(input.DeviceID),
		DeviceNonceHash: store.HashAccessToken(input.DeviceNonce),
		DeviceTokenHash: store.HashAccessToken(deviceToken), At: time.Now().UTC(),
	})
	if errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusUnauthorized, "device activation is invalid or unavailable")
		return
	}
	if err != nil {
		s.config.Logger.Error("consume device activation", "error", err)
		writeProblem(w, http.StatusInternalServerError, "device credential could not be issued")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"deviceToken": deviceToken, "apiOrigin": s.config.DeviceAPIOrigin,
		"deviceId": access.ID, "patientId": access.PatientID,
		"backendBindingId": access.BackendBindingID, "credentialId": access.CredentialID,
		"credentialRevision": access.CredentialRevision,
	})
}

func (s *Server) issueFamilySession(w http.ResponseWriter, r *http.Request) {
	if !s.hasTrustedFamilyOrigin(r) {
		writeProblem(w, http.StatusForbidden, "request origin is not allowed")
		return
	}
	accessToken, ok := bearerToken(r)
	if !ok {
		writeProblem(w, http.StatusUnauthorized, "family access is required")
		return
	}
	now := time.Now().UTC()
	sessionToken, err := s.randomToken()
	if err != nil {
		s.config.Logger.Error("generate family session token", "error", err)
		writeProblem(w, http.StatusInternalServerError, "family session could not be issued")
		return
	}
	csrfToken, err := s.randomToken()
	if err != nil {
		s.config.Logger.Error("generate family CSRF token", "error", err)
		writeProblem(w, http.StatusInternalServerError, "family session could not be issued")
		return
	}
	sessionID, err := s.randomUUID()
	if err != nil {
		s.config.Logger.Error("generate family session ID", "error", err)
		writeProblem(w, http.StatusInternalServerError, "family session could not be issued")
		return
	}
	expiresAt := now.Add(s.config.FamilySessionTTL)
	_, err = s.store.IssueFamilyWebSession(
		r.Context(), store.HashAccessToken(accessToken),
		store.FamilyWebSessionCredential{
			ID: sessionID, TokenHash: store.HashAccessToken(sessionToken),
			CSRFTokenHash: store.HashAccessToken(csrfToken), ExpiresAt: expiresAt,
		},
		now,
	)
	if errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusUnauthorized, "family access is invalid or expired")
		return
	}
	if err != nil {
		s.config.Logger.Error("issue family web session", "error", err)
		writeProblem(w, http.StatusInternalServerError, "family session could not be issued")
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: familySessionCookieName, Value: sessionToken,
		Path: "/", Expires: expiresAt, MaxAge: int(s.config.FamilySessionTTL / time.Second),
		HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode,
	})
	writeJSON(w, http.StatusCreated, map[string]any{
		"csrfToken": csrfToken,
		"expiresAt": expiresAt,
	})
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) ingestMeasurement(w http.ResponseWriter, r *http.Request) {
	device, err := s.authenticateDevice(r)
	if errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusUnauthorized, "invalid device token")
		return
	}
	if err != nil {
		s.config.Logger.Error("authenticate device", "error", err)
		writeProblem(w, http.StatusInternalServerError, "device could not be authenticated")
		return
	}
	var input measurementInput
	if err := decodeJSON(w, r, &input); err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	binding, err := input.deviceBinding()
	if err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	if !device.Matches(binding) {
		writeProblem(w, http.StatusConflict, "device credential binding conflicts with active provisioning")
		return
	}
	value, err := input.measurement()
	if err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	now := time.Now().UTC()
	value.PatientID = device.PatientID
	value.ReceivedAt = now
	if err := value.Validate(now); err != nil {
		writeProblem(w, http.StatusBadRequest, err.Error())
		return
	}
	recipients, err := s.store.TelegramRecipients(r.Context(), device.PatientID)
	if err != nil {
		s.config.Logger.Error("load alert recipients", "error", err, "patientId", device.PatientID)
		writeProblem(w, http.StatusInternalServerError, "alert recipients could not be loaded")
		return
	}
	_, err = s.store.ProcessDeviceMeasurement(
		r.Context(),
		device,
		value,
		recipients,
		s.alerts.PlanMeasurement,
	)
	if errors.Is(err, store.ErrEventConflict) {
		writeProblem(w, http.StatusConflict, "eventId already belongs to different measurement data")
		return
	}
	if errors.Is(err, store.ErrCredentialConflict) {
		writeProblem(w, http.StatusConflict, "device credential binding conflicts with active provisioning")
		return
	}
	if errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusUnauthorized, "invalid device token")
		return
	}
	if err != nil {
		s.config.Logger.Error("store measurement", "error", err)
		writeProblem(w, http.StatusInternalServerError, "measurement could not be stored")
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]bool{"accepted": true})
}

func (s *Server) patientSnapshot(w http.ResponseWriter, r *http.Request) {
	session, err := s.authenticateFamily(r)
	if errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusUnauthorized, "family session is required")
		return
	}
	if err != nil {
		s.config.Logger.Error("authenticate family session", "error", err)
		writeProblem(w, http.StatusInternalServerError, "family session could not be authenticated")
		return
	}
	patientID, validPatientID := canonicalPathUUID(r.PathValue("patientId"))
	if !validPatientID {
		writeProblem(w, http.StatusBadRequest, "patientId must be a UUID")
		return
	}
	allowed, err := s.store.HouseholdCanAccessPatient(r.Context(), session.HouseholdID, patientID)
	if err != nil {
		s.config.Logger.Error("authorize patient snapshot", "error", err)
		writeProblem(w, http.StatusInternalServerError, "patient access could not be checked")
		return
	}
	if !allowed {
		writeProblem(w, http.StatusNotFound, "patient not found")
		return
	}
	snapshot, err := s.store.PatientSnapshot(r.Context(), patientID)
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "snapshot could not be loaded")
		return
	}
	snapshot.PatientID = patientID
	if snapshot.Latest == nil {
		snapshot.Freshness = domain.FreshnessMissing
		writeJSON(w, http.StatusOK, snapshot)
		return
	}
	snapshot.Freshness = domain.FreshnessFresh
	if snapshot.Latest.Quality != domain.QualityValid ||
		time.Since(snapshot.Latest.FreshnessTime()) >= s.config.FreshAfter {
		snapshot.Freshness = domain.FreshnessStale
	}
	writeJSON(w, http.StatusOK, snapshot)
}

func (s *Server) listMeasurements(w http.ResponseWriter, r *http.Request) {
	session, err := s.authenticateFamily(r)
	if errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusUnauthorized, "family session is required")
		return
	}
	if err != nil {
		s.config.Logger.Error("authenticate family session", "error", err)
		writeProblem(w, http.StatusInternalServerError, "family session could not be authenticated")
		return
	}
	patientID, validPatientID := canonicalPathUUID(r.PathValue("patientId"))
	if !validPatientID {
		writeProblem(w, http.StatusBadRequest, "patientId must be a UUID")
		return
	}
	allowed, err := s.store.HouseholdCanAccessPatient(r.Context(), session.HouseholdID, patientID)
	if err != nil {
		s.config.Logger.Error("authorize measurement history", "error", err)
		writeProblem(w, http.StatusInternalServerError, "patient access could not be checked")
		return
	}
	if !allowed {
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
	session, err := s.authenticateFamily(r)
	if errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusUnauthorized, "family session is required")
		return
	}
	if err != nil {
		s.config.Logger.Error("authenticate family session", "error", err)
		writeProblem(w, http.StatusInternalServerError, "family session could not be authenticated")
		return
	}
	if !s.authorizeFamilyMutation(r, session) {
		writeProblem(w, http.StatusForbidden, "request origin or CSRF token is invalid")
		return
	}
	alertID, validAlertID := canonicalPathUUID(r.PathValue("alertId"))
	if !validAlertID {
		writeProblem(w, http.StatusBadRequest, "alertId must be a UUID")
		return
	}
	if err := s.store.AcknowledgeAlertForHousehold(r.Context(), session.HouseholdID, alertID, time.Now().UTC()); errors.Is(err, store.ErrNotFound) {
		writeProblem(w, http.StatusNotFound, "alert not found")
		return
	} else if err != nil {
		writeProblem(w, http.StatusInternalServerError, "alert could not be acknowledged")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func canonicalPathUUID(value string) (string, bool) {
	if !domain.IsUUID(value) {
		return "", false
	}
	return strings.ToLower(value), true
}

func (s *Server) authenticateDevice(r *http.Request) (store.DeviceAccess, error) {
	token, ok := bearerToken(r)
	if !ok {
		return store.DeviceAccess{}, store.ErrNotFound
	}
	return s.store.ResolveActiveDevice(r.Context(), store.HashAccessToken(token), time.Now().UTC())
}

func (s *Server) authenticateFamily(r *http.Request) (store.FamilyWebSessionAccess, error) {
	var sessionToken string
	count := 0
	for _, cookie := range r.Cookies() {
		if cookie.Name == familySessionCookieName {
			count++
			sessionToken = cookie.Value
		}
	}
	if count != 1 || sessionToken == "" || len(sessionToken) > 4096 {
		return store.FamilyWebSessionAccess{}, store.ErrNotFound
	}
	return s.store.ResolveActiveFamilyWebSession(
		r.Context(), store.HashAccessToken(sessionToken), time.Now().UTC(),
	)
}

func (s *Server) authorizeFamilyMutation(r *http.Request, session store.FamilyWebSessionAccess) bool {
	if !s.hasTrustedFamilyOrigin(r) || len(session.CSRFTokenHash) != store.AccessTokenHashSize {
		return false
	}
	values := r.Header.Values("X-CSRF-Token")
	if len(values) != 1 {
		return false
	}
	token := strings.TrimSpace(values[0])
	if token == "" || len(token) > 4096 {
		return false
	}
	return subtle.ConstantTimeCompare(store.HashAccessToken(token), session.CSRFTokenHash) == 1
}

func (s *Server) hasTrustedFamilyOrigin(r *http.Request) bool {
	values := r.Header.Values("Origin")
	if len(values) != 1 {
		return false
	}
	_, ok := s.origins[strings.TrimSpace(values[0])]
	return ok
}

func (s *Server) randomToken() (string, error) {
	return deviceprovisioning.GenerateOpaqueToken(s.config.Random)
}

func (s *Server) randomUUID() (string, error) {
	return deviceprovisioning.GenerateUUID(s.config.Random)
}

type deviceProvisionInput struct {
	ActivationCode string `json:"activationCode"`
	DeviceID       string `json:"deviceId"`
	DeviceNonce    string `json:"deviceNonce"`
}

type measurementInput struct {
	DeviceID           string                    `json:"deviceId"`
	BackendBindingID   string                    `json:"backendBindingId"`
	CredentialID       string                    `json:"credentialId"`
	CredentialRevision *int64                    `json:"credentialRevision"`
	EventID            string                    `json:"eventId"`
	SensorID           string                    `json:"sensorId"`
	SensorFamily       domain.SensorFamily       `json:"sensorFamily"`
	SensorTime         time.Time                 `json:"sensorTime"`
	PhoneTime          time.Time                 `json:"phoneTime"`
	GlucoseMgDL        int                       `json:"glucoseMgDl"`
	TrendMgDLPerMinute float64                   `json:"trendMgDlPerMinute"`
	Quality            domain.MeasurementQuality `json:"quality"`
	Sequence           *uint64                   `json:"sequence"`
}

func (i measurementInput) deviceBinding() (store.DeviceBinding, error) {
	if !domain.IsUUID(i.DeviceID) {
		return store.DeviceBinding{}, errors.New("deviceId must be a UUID")
	}
	if i.CredentialRevision == nil {
		return store.DeviceBinding{}, errors.New("credentialRevision is required")
	}
	result := store.DeviceBinding{
		DeviceID: strings.ToLower(i.DeviceID), BackendBindingID: i.BackendBindingID,
		CredentialID: i.CredentialID, CredentialRevision: *i.CredentialRevision,
	}
	if err := result.Validate(); err != nil {
		return store.DeviceBinding{}, err
	}
	return result, nil
}

func (i measurementInput) measurement() (domain.Measurement, error) {
	if i.Sequence == nil {
		return domain.Measurement{}, errors.New("sequence is required")
	}
	return domain.Measurement{
		EventID: i.EventID, SensorID: i.SensorID, SensorFamily: i.SensorFamily,
		SensorTime: i.SensorTime, PhoneTime: i.PhoneTime,
		GlucoseMgDL: i.GlucoseMgDL, TrendMgDLPerMinute: i.TrendMgDLPerMinute,
		Quality: i.Quality, Sequence: *i.Sequence,
	}, nil
}

func bearerToken(r *http.Request) (string, bool) {
	values := r.Header.Values("Authorization")
	if len(values) != 1 {
		return "", false
	}
	value := strings.TrimSpace(values[0])
	if !strings.HasPrefix(value, "Bearer ") {
		return "", false
	}
	token := strings.TrimSpace(strings.TrimPrefix(value, "Bearer "))
	if token == "" || len(token) > 4096 {
		return "", false
	}
	return token, true
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
