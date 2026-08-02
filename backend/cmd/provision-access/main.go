package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

type provisionInput struct {
	HouseholdID            string     `json:"householdId"`
	HouseholdName          string     `json:"householdName"`
	PatientID              string     `json:"patientId"`
	PatientName            string     `json:"patientName"`
	DeviceID               string     `json:"deviceId"`
	DeviceName             string     `json:"deviceName"`
	DeviceToken            string     `json:"deviceToken"`
	BackendBindingID       string     `json:"backendBindingId"`
	CredentialID           string     `json:"credentialId"`
	CredentialRevision     int64      `json:"credentialRevision"`
	FamilySessionID        string     `json:"familySessionId"`
	FamilySessionToken     string     `json:"familySessionToken"`
	FamilySessionExpiresAt *time.Time `json:"familySessionExpiresAt"`
	TelegramChatIDs        []string   `json:"telegramChatIds"`
}

func main() {
	databaseURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if databaseURL == "" {
		fail(errors.New("DATABASE_URL is required"))
	}
	identity, err := decodeProvisionInput(os.Stdin)
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
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		fail(fmt.Errorf("provision access: %w", err))
	}
	fmt.Printf("provisioned household=%s patient=%s device=%s familySession=%s\n",
		identity.HouseholdID, identity.PatientID, identity.DeviceID, identity.FamilySessionID)
}

func decodeProvisionInput(reader io.Reader) (store.BootstrapIdentity, error) {
	decoder := json.NewDecoder(io.LimitReader(reader, 64<<10))
	decoder.DisallowUnknownFields()
	var input provisionInput
	if err := decoder.Decode(&input); err != nil {
		return store.BootstrapIdentity{}, fmt.Errorf("invalid provisioning JSON: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return store.BootstrapIdentity{}, errors.New("provisioning input must contain exactly one JSON object")
	}
	for name, value := range map[string]string{
		"householdId":     input.HouseholdID,
		"patientId":       input.PatientID,
		"deviceId":        input.DeviceID,
		"familySessionId": input.FamilySessionID,
	} {
		if !domain.IsUUID(value) {
			return store.BootstrapIdentity{}, errors.New(name + " must be a UUID")
		}
	}
	input.HouseholdID = strings.ToLower(input.HouseholdID)
	input.PatientID = strings.ToLower(input.PatientID)
	input.DeviceID = strings.ToLower(input.DeviceID)
	input.FamilySessionID = strings.ToLower(input.FamilySessionID)
	input.PatientName = domain.NormalizePatientDisplayName(input.PatientName)
	if input.PatientName == "" {
		return store.BootstrapIdentity{}, errors.New("patientName must contain a readable display name")
	}
	if len(input.DeviceToken) < 32 || len(input.DeviceToken) > 4096 ||
		len(input.FamilySessionToken) < 32 || len(input.FamilySessionToken) > 4096 {
		return store.BootstrapIdentity{}, errors.New("device and family tokens must each contain 32..4096 characters")
	}
	if strings.TrimSpace(input.DeviceToken) != input.DeviceToken ||
		strings.TrimSpace(input.FamilySessionToken) != input.FamilySessionToken {
		return store.BootstrapIdentity{}, errors.New("access tokens cannot start or end with whitespace")
	}
	if len(input.DeviceToken) == len(input.FamilySessionToken) &&
		subtle.ConstantTimeCompare([]byte(input.DeviceToken), []byte(input.FamilySessionToken)) == 1 {
		return store.BootstrapIdentity{}, errors.New("device and family tokens must be distinct")
	}
	if err := (store.DeviceBinding{
		DeviceID: input.DeviceID, BackendBindingID: input.BackendBindingID,
		CredentialID: input.CredentialID, CredentialRevision: input.CredentialRevision,
	}).Validate(); err != nil {
		return store.BootstrapIdentity{}, errors.New("device credential binding is incomplete or malformed")
	}
	hasTelegramRecipient := false
	for _, recipient := range input.TelegramChatIDs {
		if strings.TrimSpace(recipient) != "" {
			hasTelegramRecipient = true
			break
		}
	}
	if !hasTelegramRecipient {
		return store.BootstrapIdentity{}, errors.New("telegramChatIds must contain at least one non-empty recipient")
	}
	identity := store.BootstrapIdentity{
		HouseholdID: input.HouseholdID, HouseholdName: input.HouseholdName,
		PatientID: input.PatientID, PatientName: input.PatientName,
		DeviceID: input.DeviceID, DeviceName: input.DeviceName,
		DeviceTokenHash:        store.HashAccessToken(input.DeviceToken),
		BackendBindingID:       input.BackendBindingID,
		CredentialID:           input.CredentialID,
		CredentialRevision:     input.CredentialRevision,
		FamilySessionID:        input.FamilySessionID,
		FamilyTokenHash:        store.HashAccessToken(input.FamilySessionToken),
		FamilySessionExpiresAt: input.FamilySessionExpiresAt,
		TelegramRecipients:     input.TelegramChatIDs,
	}
	input.DeviceToken = ""
	input.FamilySessionToken = ""
	return identity, nil
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
