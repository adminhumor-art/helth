package main

import (
	"bytes"
	"fmt"
	"strings"
	"testing"

	"glucose-monitor/backend/internal/store"
)

func TestDecodeProvisionInputHashesSecretsAndKeepsThemOutOfResult(t *testing.T) {
	deviceToken := "device-token-0123456789abcdef0123456789"
	familyToken := "family-token-0123456789abcdef0123456789"
	payload := fmt.Sprintf(`{
		"householdId":"00000000-0000-4000-8000-000000000101",
		"householdName":"Семья",
		"patientId":"00000000-0000-4000-8000-000000000001",
		"patientName":"Мама",
		"deviceId":"00000000-0000-4000-8000-000000000201",
		"deviceName":"Samsung",
		"deviceToken":%q,
		"backendBindingId":"backend-binding-1",
		"credentialId":"credential-1",
		"credentialRevision":1,
		"familySessionId":"00000000-0000-4000-8000-000000000301",
		"familySessionToken":%q,
		"telegramChatIds":["chat-b","chat-a"]
	}`, deviceToken, familyToken)

	identity, err := decodeProvisionInput(strings.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(identity.DeviceTokenHash, store.HashAccessToken(deviceToken)) ||
		!bytes.Equal(identity.FamilyTokenHash, store.HashAccessToken(familyToken)) {
		t.Fatal("provisioning input did not produce exact token digests")
	}
	dump := fmt.Sprintf("%#v", identity)
	if strings.Contains(dump, deviceToken) || strings.Contains(dump, familyToken) {
		t.Fatal("provisioning result retained a raw token")
	}
}

func TestDecodeProvisionInputRequiresNonEmptyTelegramRecipient(t *testing.T) {
	for _, recipients := range []string{`[]`, `["", "  "]`} {
		payload := fmt.Sprintf(`{
			"householdId":"00000000-0000-4000-8000-000000000101",
			"householdName":"Семья",
			"patientId":"00000000-0000-4000-8000-000000000001",
			"patientName":"Мама",
			"deviceId":"00000000-0000-4000-8000-000000000201",
			"deviceName":"Samsung",
			"deviceToken":"device-token-0123456789abcdef0123456789",
			"backendBindingId":"backend-binding-1",
			"credentialId":"credential-1",
			"credentialRevision":1,
			"familySessionId":"00000000-0000-4000-8000-000000000301",
			"familySessionToken":"family-token-0123456789abcdef0123456789",
			"telegramChatIds":%s
		}`, recipients)
		if _, err := decodeProvisionInput(strings.NewReader(payload)); err == nil ||
			!strings.Contains(err.Error(), "telegramChatIds") {
			t.Fatalf("production provisioning accepted recipients %s: %v", recipients, err)
		}
	}
}

func TestDecodeProvisionInputRequiresUnderstandableBoundedPatientName(t *testing.T) {
	payload := func(patientName string) string {
		return fmt.Sprintf(`{
			"householdId":"00000000-0000-4000-8000-000000000101",
			"householdName":"Семья",
			"patientId":"00000000-0000-4000-8000-000000000001",
			"patientName":%q,
			"deviceId":"00000000-0000-4000-8000-000000000201",
			"deviceName":"Samsung",
			"deviceToken":"device-token-0123456789abcdef0123456789",
			"backendBindingId":"backend-binding-1",
			"credentialId":"credential-1",
			"credentialRevision":1,
			"familySessionId":"00000000-0000-4000-8000-000000000301",
			"familySessionToken":"family-token-0123456789abcdef0123456789",
			"telegramChatIds":["123456789"]
		}`, patientName)
	}
	if _, err := decodeProvisionInput(strings.NewReader(payload("\u202e\n"))); err == nil ||
		!strings.Contains(err.Error(), "patientName") {
		t.Fatalf("production provisioning accepted an unreadable patientName: %v", err)
	}
	identity, err := decodeProvisionInput(strings.NewReader(payload("  Мама\n\t Иванова  ")))
	if err != nil {
		t.Fatal(err)
	}
	if identity.PatientName != "Мама Иванова" {
		t.Fatalf("patientName was not safely normalized: %q", identity.PatientName)
	}
	if count := len([]rune(identity.PatientName)); count > 80 {
		t.Fatalf("patientName contains %d runes", count)
	}
}

func TestDecodeProvisionInputRejectsUnsafeOrAmbiguousInputWithoutEchoingSecrets(t *testing.T) {
	secret := "same-token-0123456789abcdef0123456789"
	for _, payload := range []string{
		`{"deviceToken":"short","familySessionToken":"also-short"}`,
		fmt.Sprintf(`{
			"householdId":"00000000-0000-4000-8000-000000000101",
			"patientId":"00000000-0000-4000-8000-000000000001",
			"deviceId":"00000000-0000-4000-8000-000000000201",
			"deviceToken":%q,
			"backendBindingId":"backend-binding-1",
			"credentialId":"credential-1",
			"credentialRevision":1,
			"familySessionId":"00000000-0000-4000-8000-000000000301",
			"familySessionToken":%q
		}`, secret, secret),
		`{"unknown":true}`,
		`{} {}`,
	} {
		_, err := decodeProvisionInput(strings.NewReader(payload))
		if err == nil {
			t.Fatalf("unsafe provisioning input was accepted: %s", payload)
		}
		if strings.Contains(err.Error(), secret) {
			t.Fatal("provisioning error echoed a raw token")
		}
	}
}

func TestDecodeProvisionInputCanonicalizesUUIDsAndEnforcesCredentialRevisionBoundary(t *testing.T) {
	payload := func(revision int64) string {
		return fmt.Sprintf(`{
			"householdId":"AAAAAAAA-0000-4000-8000-000000000101",
			"patientId":"BBBBBBBB-0000-4000-8000-000000000001",
			"patientName":"Мама",
			"deviceId":"CCCCCCCC-0000-4000-8000-000000000201",
			"deviceToken":"device-token-0123456789abcdef0123456789",
			"backendBindingId":"backend-binding-1",
			"credentialId":"credential-1",
			"credentialRevision":%d,
			"familySessionId":"DDDDDDDD-0000-4000-8000-000000000301",
			"familySessionToken":"family-token-0123456789abcdef0123456789",
			"telegramChatIds":["123456789"]
		}`, revision)
	}
	identity, err := decodeProvisionInput(strings.NewReader(payload(store.MaxCredentialRevision)))
	if err != nil {
		t.Fatalf("maximum JSON-safe revision was rejected: %v", err)
	}
	for name, value := range map[string]string{
		"household":     identity.HouseholdID,
		"patient":       identity.PatientID,
		"device":        identity.DeviceID,
		"familySession": identity.FamilySessionID,
	} {
		if value != strings.ToLower(value) {
			t.Fatalf("%s UUID is not canonical lowercase: %q", name, value)
		}
	}
	if _, err := decodeProvisionInput(strings.NewReader(payload(store.MaxCredentialRevision + 1))); err == nil {
		t.Fatal("provisioning accepted credentialRevision outside the JSON safe-integer range")
	}
}
