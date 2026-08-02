package store

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestMemoryIssuesFamilyWebSessionOnlyFromActiveProvisionedAccess(t *testing.T) {
	now := time.Date(2026, 8, 2, 10, 0, 0, 0, time.UTC)
	values := NewMemory()
	identity := BootstrapIdentity{
		HouseholdID:            "00000000-0000-4000-8000-000000000101",
		PatientID:              "00000000-0000-4000-8000-000000000001",
		DeviceID:               "00000000-0000-4000-8000-000000000201",
		DeviceTokenHash:        HashAccessToken("device-access"),
		BackendBindingID:       "binding-1",
		CredentialID:           "credential-1",
		CredentialRevision:     1,
		FamilySessionID:        "00000000-0000-4000-8000-000000000301",
		FamilyTokenHash:        HashAccessToken("family-access"),
		FamilySessionExpiresAt: timePointer(now.Add(time.Hour)),
	}
	if err := values.BootstrapAccess(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	credential := FamilyWebSessionCredential{
		ID:            "00000000-0000-4000-8000-000000000401",
		TokenHash:     HashAccessToken("browser-session"),
		CSRFTokenHash: HashAccessToken("csrf-token"),
		ExpiresAt:     now.Add(30 * time.Minute),
	}
	issued, err := values.IssueFamilyWebSession(
		context.Background(), identity.FamilyTokenHash, credential, now,
	)
	if err != nil {
		t.Fatal(err)
	}
	if issued.ID != credential.ID || issued.HouseholdID != identity.HouseholdID {
		t.Fatalf("issued session is not bound to provisioned family: %#v", issued)
	}
	resolved, err := values.ResolveActiveFamilyWebSession(
		context.Background(), credential.TokenHash, now.Add(time.Minute),
	)
	if err != nil {
		t.Fatal(err)
	}
	if resolved.ID != credential.ID || resolved.HouseholdID != identity.HouseholdID ||
		string(resolved.CSRFTokenHash) != string(credential.CSRFTokenHash) {
		t.Fatalf("resolved session lost its binding or CSRF digest: %#v", resolved)
	}
	if _, err := values.ResolveActiveFamilyWebSession(context.Background(), identity.FamilyTokenHash, now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("provisioned family access became a browser session: %v", err)
	}
	if _, err := values.IssueFamilyWebSession(
		context.Background(), HashAccessToken("unknown-family-access"),
		FamilyWebSessionCredential{
			ID: "00000000-0000-4000-8000-000000000402", TokenHash: HashAccessToken("other-session"),
			CSRFTokenHash: HashAccessToken("other-csrf"), ExpiresAt: now.Add(30 * time.Minute),
		}, now,
	); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown family access issued a browser session: %v", err)
	}
}

func TestMemoryFamilyWebSessionExpiresWithEitherBound(t *testing.T) {
	now := time.Date(2026, 8, 2, 10, 0, 0, 0, time.UTC)
	accessExpiresAt := now.Add(20 * time.Minute)
	values := NewMemory()
	identity := BootstrapIdentity{
		HouseholdID:            "00000000-0000-4000-8000-000000000101",
		PatientID:              "00000000-0000-4000-8000-000000000001",
		DeviceID:               "00000000-0000-4000-8000-000000000201",
		DeviceTokenHash:        HashAccessToken("device-access"),
		BackendBindingID:       "binding-1",
		CredentialID:           "credential-1",
		CredentialRevision:     1,
		FamilySessionID:        "00000000-0000-4000-8000-000000000301",
		FamilyTokenHash:        HashAccessToken("family-access"),
		FamilySessionExpiresAt: &accessExpiresAt,
	}
	if err := values.BootstrapAccess(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	credential := FamilyWebSessionCredential{
		ID: "00000000-0000-4000-8000-000000000401", TokenHash: HashAccessToken("browser-session"),
		CSRFTokenHash: HashAccessToken("csrf-token"), ExpiresAt: now.Add(10 * time.Minute),
	}
	if _, err := values.IssueFamilyWebSession(context.Background(), identity.FamilyTokenHash, credential, now); err != nil {
		t.Fatal(err)
	}
	if _, err := values.ResolveActiveFamilyWebSession(context.Background(), credential.TokenHash, credential.ExpiresAt); !errors.Is(err, ErrNotFound) {
		t.Fatalf("browser session stayed active at its expiry: %v", err)
	}

	second := FamilyWebSessionCredential{
		ID: "00000000-0000-4000-8000-000000000402", TokenHash: HashAccessToken("long-browser-session"),
		CSRFTokenHash: HashAccessToken("long-csrf-token"), ExpiresAt: now.Add(time.Hour),
	}
	if _, err := values.IssueFamilyWebSession(context.Background(), identity.FamilyTokenHash, second, now); err != nil {
		t.Fatal(err)
	}
	if _, err := values.ResolveActiveFamilyWebSession(context.Background(), second.TokenHash, accessExpiresAt); !errors.Is(err, ErrNotFound) {
		t.Fatalf("browser session outlived its provisioned family access: %v", err)
	}
}
