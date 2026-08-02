package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"
	"unicode/utf8"

	"glucose-monitor/backend/internal/deviceprovisioning"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

const (
	defaultActivationTTLMinutes      int64 = 15
	installationRequestPrefix              = "SLKI1."
	installationRequestEncodedLength       = 148
	installationRequestDecodedLength       = 111
	installationRequestLength              = len(installationRequestPrefix) + installationRequestEncodedLength
)

type provisionInput struct {
	HouseholdID            string     `json:"householdId"`
	HouseholdName          string     `json:"householdName"`
	PatientID              string     `json:"patientId"`
	PatientName            string     `json:"patientName"`
	DeviceName             string     `json:"deviceName"`
	InstallationRequest    string     `json:"installationRequest"`
	BackendBindingID       string     `json:"backendBindingId"`
	CredentialID           string     `json:"credentialId"`
	CredentialRevision     int64      `json:"credentialRevision"`
	FamilySessionID        string     `json:"familySessionId"`
	FamilySessionToken     string     `json:"familySessionToken"`
	FamilySessionExpiresAt *time.Time `json:"familySessionExpiresAt"`
	ActivationTTLMinutes   *int64     `json:"activationTtlMinutes"`
	TelegramChatIDs        []string   `json:"telegramChatIds"`
}

type installationIdentity struct {
	DeviceID    string `json:"deviceId"`
	DeviceNonce string `json:"deviceNonce"`
}

type preparedProvisioning struct {
	Plan           store.DeviceActivationProvisioning
	ActivationCode string
}

func main() {
	databaseURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if databaseURL == "" {
		fail(errors.New("DATABASE_URL is required"))
	}
	prepared, err := prepareProvisioning(os.Stdin, rand.Reader, time.Now().UTC())
	if err != nil {
		fail(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	values, err := store.NewPostgres(ctx, databaseURL)
	if err != nil {
		fail(fmt.Errorf("connect to PostgreSQL: %w", err))
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		fail(fmt.Errorf("initialize schema: %w", err))
	}
	if err := values.ProvisionDeviceActivation(ctx, prepared.Plan); err != nil {
		fail(fmt.Errorf("provision device activation: %w", err))
	}
	if err := writeProvisionOutput(os.Stdout, prepared); err != nil {
		fail(fmt.Errorf("write activation output: %w", err))
	}
}

func prepareProvisioning(reader io.Reader, random io.Reader, now time.Time) (preparedProvisioning, error) {
	decoder := json.NewDecoder(io.LimitReader(reader, 64<<10))
	decoder.DisallowUnknownFields()
	var input provisionInput
	if err := decoder.Decode(&input); err != nil {
		return preparedProvisioning{}, fmt.Errorf("invalid provisioning JSON: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return preparedProvisioning{}, errors.New("provisioning input must contain exactly one JSON object")
	}
	installation, err := parseInstallationRequest(input.InstallationRequest)
	if err != nil {
		return preparedProvisioning{}, err
	}
	for name, value := range map[string]string{
		"householdId":     input.HouseholdID,
		"patientId":       input.PatientID,
		"familySessionId": input.FamilySessionID,
	} {
		if !domain.IsUUID(value) {
			return preparedProvisioning{}, errors.New(name + " must be a UUID")
		}
	}
	input.HouseholdID = strings.ToLower(input.HouseholdID)
	input.PatientID = strings.ToLower(input.PatientID)
	input.FamilySessionID = strings.ToLower(input.FamilySessionID)
	input.PatientName = domain.NormalizePatientDisplayName(input.PatientName)
	if input.PatientName == "" {
		return preparedProvisioning{}, errors.New("patientName must contain a readable display name")
	}
	if len(input.FamilySessionToken) < 32 || len(input.FamilySessionToken) > 4096 ||
		strings.TrimSpace(input.FamilySessionToken) != input.FamilySessionToken {
		return preparedProvisioning{}, errors.New("familySessionToken must contain 32..4096 unpadded characters")
	}
	if len(input.FamilySessionToken) == len(installation.DeviceNonce) &&
		subtle.ConstantTimeCompare([]byte(input.FamilySessionToken), []byte(installation.DeviceNonce)) == 1 {
		return preparedProvisioning{}, errors.New("family access and device nonce must be distinct")
	}
	if err := (store.DeviceBinding{
		DeviceID: installation.DeviceID, BackendBindingID: input.BackendBindingID,
		CredentialID: input.CredentialID, CredentialRevision: input.CredentialRevision,
	}).Validate(); err != nil {
		return preparedProvisioning{}, errors.New("device credential binding is incomplete or malformed")
	}
	ttlMinutes := defaultActivationTTLMinutes
	if input.ActivationTTLMinutes != nil {
		ttlMinutes = *input.ActivationTTLMinutes
	}
	if ttlMinutes <= 0 || time.Duration(ttlMinutes)*time.Minute > store.MaxDeviceActivationLifetime {
		return preparedProvisioning{}, errors.New("activationTtlMinutes must be within 1..30")
	}
	hasTelegramRecipient := false
	for _, recipient := range input.TelegramChatIDs {
		if strings.TrimSpace(recipient) != "" {
			hasTelegramRecipient = true
			break
		}
	}
	if !hasTelegramRecipient {
		return preparedProvisioning{}, errors.New("telegramChatIds must contain at least one non-empty recipient")
	}
	activationCode, err := deviceprovisioning.GenerateActivationCode(random)
	if err != nil {
		return preparedProvisioning{}, errors.New("activation code randomness is unavailable")
	}
	activationID, err := deviceprovisioning.GenerateUUID(random)
	if err != nil {
		return preparedProvisioning{}, errors.New("activation ID randomness is unavailable")
	}
	now = now.UTC()
	prepared := preparedProvisioning{
		Plan: store.DeviceActivationProvisioning{
			Identity: store.BootstrapIdentity{
				HouseholdID: input.HouseholdID, HouseholdName: input.HouseholdName,
				PatientID: input.PatientID, PatientName: input.PatientName,
				DeviceID: installation.DeviceID, DeviceName: input.DeviceName,
				BackendBindingID: input.BackendBindingID, CredentialID: input.CredentialID,
				CredentialRevision:     input.CredentialRevision,
				FamilySessionID:        input.FamilySessionID,
				FamilyTokenHash:        store.HashAccessToken(input.FamilySessionToken),
				FamilySessionExpiresAt: input.FamilySessionExpiresAt,
				TelegramRecipients:     input.TelegramChatIDs,
			},
			Activation: store.DeviceActivationCredential{
				ID: activationID, CodeHash: store.HashAccessToken(activationCode),
				DeviceNonceHash: store.HashAccessToken(installation.DeviceNonce),
				CreatedAt:       now, ExpiresAt: now.Add(time.Duration(ttlMinutes) * time.Minute),
			},
		},
		ActivationCode: activationCode,
	}
	input.InstallationRequest = ""
	installation.DeviceNonce = ""
	input.FamilySessionToken = ""
	return prepared, nil
}

func parseInstallationRequest(request string) (installationIdentity, error) {
	invalid := func() (installationIdentity, error) {
		return installationIdentity{}, errors.New("installationRequest is invalid or non-canonical")
	}
	if len(request) != installationRequestLength || !strings.HasPrefix(request, installationRequestPrefix) {
		return invalid()
	}
	encoded := request[len(installationRequestPrefix):]
	for i := 0; i < len(encoded); i++ {
		value := encoded[i]
		if !((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') ||
			(value >= '0' && value <= '9') || value == '_' || value == '-') {
			return invalid()
		}
	}
	decoded, err := base64.RawURLEncoding.Strict().DecodeString(encoded)
	if err != nil || len(decoded) != installationRequestDecodedLength || !utf8.Valid(decoded) ||
		base64.RawURLEncoding.EncodeToString(decoded) != encoded {
		return invalid()
	}
	var identity installationIdentity
	decoder := json.NewDecoder(bytes.NewReader(decoded))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&identity); err != nil {
		return invalid()
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return invalid()
	}
	if !domain.IsUUID(identity.DeviceID) || identity.DeviceID != strings.ToLower(identity.DeviceID) ||
		!deviceprovisioning.ValidDeviceNonce(identity.DeviceNonce) {
		return invalid()
	}
	canonical, err := json.Marshal(identity)
	if err != nil || !bytes.Equal(canonical, decoded) {
		return invalid()
	}
	return identity, nil
}

func writeProvisionOutput(writer io.Writer, prepared preparedProvisioning) error {
	return json.NewEncoder(writer).Encode(map[string]any{
		"activationCode": prepared.ActivationCode,
		"expiresAt":      prepared.Plan.Activation.ExpiresAt,
		"householdId":    prepared.Plan.Identity.HouseholdID,
		"patientId":      prepared.Plan.Identity.PatientID,
		"deviceId":       prepared.Plan.Identity.DeviceID,
	})
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
