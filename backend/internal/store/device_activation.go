package store

import (
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"time"

	"glucose-monitor/backend/internal/domain"
)

const MaxDeviceActivationLifetime = 30 * time.Minute

type DeviceActivationCredential struct {
	ID              string
	CodeHash        []byte
	DeviceNonceHash []byte
	CreatedAt       time.Time
	ExpiresAt       time.Time
}

type DeviceActivationProvisioning struct {
	Identity   BootstrapIdentity
	Activation DeviceActivationCredential
}

type DeviceActivationConsume struct {
	CodeHash        []byte
	DeviceID        string
	DeviceNonceHash []byte
	DeviceTokenHash []byte
	At              time.Time
}

type memoryDeviceActivationCredential struct {
	DeviceActivationCredential
	DeviceID   string
	ConsumedAt *time.Time
}

func normalizeDeviceActivationProvisioning(value DeviceActivationProvisioning) DeviceActivationProvisioning {
	value.Identity = normalizeBootstrapIdentity(value.Identity)
	value.Activation.ID = canonicalUUID(value.Activation.ID)
	value.Activation.CodeHash = append([]byte(nil), value.Activation.CodeHash...)
	value.Activation.DeviceNonceHash = append([]byte(nil), value.Activation.DeviceNonceHash...)
	value.Activation.CreatedAt = value.Activation.CreatedAt.UTC()
	value.Activation.ExpiresAt = value.Activation.ExpiresAt.UTC()
	return value
}

func validateDeviceActivationProvisioning(value DeviceActivationProvisioning) error {
	identity := value.Identity
	activation := value.Activation
	if identity.HouseholdID == "" || identity.PatientID == "" || identity.DeviceID == "" || identity.FamilySessionID == "" {
		return errors.New("household, patient, device and family session IDs are required")
	}
	if len(identity.DeviceTokenHash) != 0 {
		return errors.New("pending device provisioning cannot contain a device token digest")
	}
	if len(identity.FamilyTokenHash) != AccessTokenHashSize {
		return fmt.Errorf("family access token digest must contain exactly %d bytes", AccessTokenHashSize)
	}
	if err := (DeviceBinding{
		DeviceID: identity.DeviceID, BackendBindingID: identity.BackendBindingID,
		CredentialID: identity.CredentialID, CredentialRevision: identity.CredentialRevision,
	}).Validate(); err != nil {
		return err
	}
	if !domain.IsUUID(activation.ID) {
		return errors.New("device activation ID must be a UUID")
	}
	if len(activation.CodeHash) != AccessTokenHashSize || len(activation.DeviceNonceHash) != AccessTokenHashSize {
		return fmt.Errorf("device activation digests must contain exactly %d bytes", AccessTokenHashSize)
	}
	if activation.CreatedAt.IsZero() || !activation.ExpiresAt.After(activation.CreatedAt) ||
		activation.ExpiresAt.Sub(activation.CreatedAt) > MaxDeviceActivationLifetime {
		return errors.New("device activation lifetime must be positive and at most 30 minutes")
	}
	for _, pair := range [][2][]byte{
		{activation.CodeHash, activation.DeviceNonceHash},
		{activation.CodeHash, identity.FamilyTokenHash},
		{activation.DeviceNonceHash, identity.FamilyTokenHash},
	} {
		if subtle.ConstantTimeCompare(pair[0], pair[1]) == 1 {
			return ErrCredentialConflict
		}
	}
	return nil
}

func validateDeviceActivationConsume(value DeviceActivationConsume) error {
	if !domain.IsUUID(value.DeviceID) {
		return errors.New("device ID must be a UUID")
	}
	for _, digest := range [][]byte{value.CodeHash, value.DeviceNonceHash, value.DeviceTokenHash} {
		if len(digest) != AccessTokenHashSize {
			return fmt.Errorf("device activation digests must contain exactly %d bytes", AccessTokenHashSize)
		}
	}
	if value.At.IsZero() {
		return errors.New("device activation consume time is required")
	}
	if subtle.ConstantTimeCompare(value.CodeHash, value.DeviceNonceHash) == 1 ||
		subtle.ConstantTimeCompare(value.CodeHash, value.DeviceTokenHash) == 1 ||
		subtle.ConstantTimeCompare(value.DeviceNonceHash, value.DeviceTokenHash) == 1 {
		return ErrCredentialConflict
	}
	return nil
}

func (m *Memory) ProvisionDeviceActivation(_ context.Context, value DeviceActivationProvisioning) error {
	value = normalizeDeviceActivationProvisioning(value)
	if err := validateDeviceActivationProvisioning(value); err != nil {
		return err
	}
	identity := value.Identity
	activation := value.Activation
	m.mu.Lock()
	defer m.mu.Unlock()

	for id, device := range m.devices {
		if subtle.ConstantTimeCompare(device.TokenHash, identity.FamilyTokenHash) == 1 ||
			subtle.ConstantTimeCompare(device.TokenHash, activation.CodeHash) == 1 ||
			subtle.ConstantTimeCompare(device.TokenHash, activation.DeviceNonceHash) == 1 ||
			(id != identity.DeviceID && (exactText(device.BackendBindingID, identity.BackendBindingID) ||
				(exactText(device.CredentialID, identity.CredentialID) &&
					device.CredentialRevision == identity.CredentialRevision))) {
			return ErrCredentialConflict
		}
	}
	for id, session := range m.familySessions {
		if subtle.ConstantTimeCompare(session.TokenHash, activation.CodeHash) == 1 ||
			subtle.ConstantTimeCompare(session.TokenHash, activation.DeviceNonceHash) == 1 ||
			(subtle.ConstantTimeCompare(session.TokenHash, identity.FamilyTokenHash) == 1 && id != identity.FamilySessionID) {
			return ErrCredentialConflict
		}
	}
	for _, session := range m.familyWebSessions {
		if subtle.ConstantTimeCompare(session.TokenHash, activation.CodeHash) == 1 ||
			subtle.ConstantTimeCompare(session.TokenHash, activation.DeviceNonceHash) == 1 ||
			subtle.ConstantTimeCompare(session.CSRFTokenHash, activation.CodeHash) == 1 ||
			subtle.ConstantTimeCompare(session.CSRFTokenHash, activation.DeviceNonceHash) == 1 {
			return ErrCredentialConflict
		}
	}
	for id, candidate := range m.deviceActivations {
		if subtle.ConstantTimeCompare(candidate.CodeHash, activation.CodeHash) == 1 && id != activation.ID {
			return ErrCredentialConflict
		}
		if subtle.ConstantTimeCompare(candidate.DeviceNonceHash, activation.DeviceNonceHash) == 1 &&
			candidate.DeviceID != identity.DeviceID {
			return ErrCredentialConflict
		}
		if candidate.DeviceID == identity.DeviceID && candidate.ConsumedAt == nil &&
			candidate.ExpiresAt.After(activation.CreatedAt) && id != activation.ID {
			return ErrCredentialConflict
		}
	}
	if name, ok := m.householdNames[identity.HouseholdID]; ok && name != identity.HouseholdName {
		return ErrCredentialConflict
	}
	if householdID, ok := m.patientHouseholds[identity.PatientID]; ok &&
		(householdID != identity.HouseholdID || m.patientNames[identity.PatientID] != identity.PatientName) {
		return ErrCredentialConflict
	}
	if existing, ok := m.devices[identity.DeviceID]; ok &&
		(existing.PatientID != identity.PatientID || existing.Name != identity.DeviceName ||
			len(existing.TokenHash) != 0 || existing.RevokedAt != nil ||
			!exactText(existing.BackendBindingID, identity.BackendBindingID) ||
			!exactText(existing.CredentialID, identity.CredentialID) ||
			existing.CredentialRevision != identity.CredentialRevision) {
		return ErrCredentialConflict
	}
	if existing, ok := m.familySessions[identity.FamilySessionID]; ok &&
		(existing.HouseholdID != identity.HouseholdID ||
			subtle.ConstantTimeCompare(existing.TokenHash, identity.FamilyTokenHash) != 1 ||
			!sameOptionalTime(existing.ExpiresAt, identity.FamilySessionExpiresAt)) {
		return ErrCredentialConflict
	}
	if recipients, ok := m.householdTelegramRecipients[identity.HouseholdID]; ok &&
		!sameStrings(recipients, identity.TelegramRecipients) {
		return ErrCredentialConflict
	}
	if existing, ok := m.deviceActivations[activation.ID]; ok &&
		(existing.DeviceID != identity.DeviceID ||
			subtle.ConstantTimeCompare(existing.CodeHash, activation.CodeHash) != 1 ||
			subtle.ConstantTimeCompare(existing.DeviceNonceHash, activation.DeviceNonceHash) != 1 ||
			!existing.CreatedAt.Equal(activation.CreatedAt) || !existing.ExpiresAt.Equal(activation.ExpiresAt)) {
		return ErrCredentialConflict
	}

	m.householdNames[identity.HouseholdID] = identity.HouseholdName
	m.patientHouseholds[identity.PatientID] = identity.HouseholdID
	m.patientNames[identity.PatientID] = identity.PatientName
	if _, exists := m.devices[identity.DeviceID]; !exists {
		m.devices[identity.DeviceID] = memoryDeviceAccess{
			DeviceAccess: DeviceAccess{
				ID: identity.DeviceID, PatientID: identity.PatientID,
				BackendBindingID: identity.BackendBindingID, CredentialID: identity.CredentialID,
				CredentialRevision: identity.CredentialRevision,
			},
			Name: identity.DeviceName,
		}
	}
	if _, exists := m.familySessions[identity.FamilySessionID]; !exists {
		m.familySessions[identity.FamilySessionID] = memoryFamilySessionAccess{
			FamilySessionAccess: FamilySessionAccess{ID: identity.FamilySessionID, HouseholdID: identity.HouseholdID},
			TokenHash:           append([]byte(nil), identity.FamilyTokenHash...),
			ExpiresAt:           cloneTime(identity.FamilySessionExpiresAt),
		}
	}
	m.householdTelegramRecipients[identity.HouseholdID] = append([]string(nil), identity.TelegramRecipients...)
	if _, exists := m.deviceActivations[activation.ID]; !exists {
		m.deviceActivations[activation.ID] = memoryDeviceActivationCredential{
			DeviceActivationCredential: activation,
			DeviceID:                   identity.DeviceID,
		}
	}
	return nil
}

func (m *Memory) ConsumeDeviceActivation(_ context.Context, value DeviceActivationConsume) (DeviceAccess, error) {
	value.DeviceID = canonicalUUID(value.DeviceID)
	value.At = value.At.UTC()
	if err := validateDeviceActivationConsume(value); err != nil {
		return DeviceAccess{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()

	var activationID string
	var activation memoryDeviceActivationCredential
	for id, candidate := range m.deviceActivations {
		active := candidate.ConsumedAt == nil && !value.At.Before(candidate.CreatedAt) && candidate.ExpiresAt.After(value.At)
		if subtle.ConstantTimeCompare(candidate.CodeHash, value.CodeHash) == 1 && active &&
			candidate.DeviceID == value.DeviceID &&
			subtle.ConstantTimeCompare(candidate.DeviceNonceHash, value.DeviceNonceHash) == 1 {
			activationID = id
			activation = candidate
		}
	}
	if activationID == "" {
		return DeviceAccess{}, ErrNotFound
	}
	device, exists := m.devices[activation.DeviceID]
	if !exists || device.RevokedAt != nil || len(device.TokenHash) != 0 {
		return DeviceAccess{}, ErrNotFound
	}
	for _, candidate := range m.devices {
		if subtle.ConstantTimeCompare(candidate.TokenHash, value.DeviceTokenHash) == 1 {
			return DeviceAccess{}, ErrCredentialConflict
		}
	}
	for _, session := range m.familySessions {
		if subtle.ConstantTimeCompare(session.TokenHash, value.DeviceTokenHash) == 1 {
			return DeviceAccess{}, ErrCredentialConflict
		}
	}
	for _, session := range m.familyWebSessions {
		if subtle.ConstantTimeCompare(session.TokenHash, value.DeviceTokenHash) == 1 ||
			subtle.ConstantTimeCompare(session.CSRFTokenHash, value.DeviceTokenHash) == 1 {
			return DeviceAccess{}, ErrCredentialConflict
		}
	}
	for _, candidate := range m.deviceActivations {
		if subtle.ConstantTimeCompare(candidate.CodeHash, value.DeviceTokenHash) == 1 ||
			subtle.ConstantTimeCompare(candidate.DeviceNonceHash, value.DeviceTokenHash) == 1 {
			return DeviceAccess{}, ErrCredentialConflict
		}
	}

	device.TokenHash = append([]byte(nil), value.DeviceTokenHash...)
	m.devices[device.ID] = device
	activation.ConsumedAt = timePointer(value.At)
	m.deviceActivations[activationID] = activation
	return device.DeviceAccess, nil
}
