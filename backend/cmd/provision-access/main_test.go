package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"glucose-monitor/backend/internal/deviceprovisioning"
	"glucose-monitor/backend/internal/store"
)

func TestPrepareProvisioningCreatesHashedShortLivedActivationWithoutDeviceToken(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	familyAccess := "family-access-0123456789abcdef0123456789"
	random := bytes.NewReader(bytes.Repeat([]byte{3}, 64))
	prepared, err := prepareProvisioning(
		strings.NewReader(validProvisionPayload(familyAccess, androidInstallationRequest, 15, 1)), random, now,
	)
	if err != nil {
		t.Fatal(err)
	}
	if !deviceprovisioning.ValidActivationCode(prepared.ActivationCode) {
		t.Fatalf("CLI generated invalid activation code %q", prepared.ActivationCode)
	}
	if len(prepared.Plan.Identity.DeviceTokenHash) != 0 {
		t.Fatal("admin provisioning created a device bearer before one-time activation")
	}
	if !bytes.Equal(prepared.Plan.Identity.FamilyTokenHash, store.HashAccessToken(familyAccess)) ||
		!bytes.Equal(prepared.Plan.Activation.CodeHash, store.HashAccessToken(prepared.ActivationCode)) ||
		!bytes.Equal(prepared.Plan.Activation.DeviceNonceHash, store.HashAccessToken(androidDeviceNonce)) {
		t.Fatal("CLI did not reduce activation inputs to exact one-way digests")
	}
	if !prepared.Plan.Activation.CreatedAt.Equal(now) ||
		!prepared.Plan.Activation.ExpiresAt.Equal(now.Add(15*time.Minute)) {
		t.Fatalf("unexpected activation lifetime: %#v", prepared.Plan.Activation)
	}
	dump := fmt.Sprintf("%#v", prepared.Plan)
	if strings.Contains(dump, familyAccess) || strings.Contains(dump, androidDeviceNonce) ||
		strings.Contains(dump, androidInstallationRequest) ||
		strings.Contains(dump, prepared.ActivationCode) {
		t.Fatal("prepared Store value retained raw provisioning material")
	}
}

func TestInstallationRequestAcceptsFixedAndroidCrossLanguageVector(t *testing.T) {
	identity, err := parseInstallationRequest(androidInstallationRequest)
	if err != nil {
		t.Fatal(err)
	}
	if identity.DeviceID != androidDeviceID || identity.DeviceNonce != androidDeviceNonce {
		t.Fatalf("unexpected decoded Android identity: %#v", identity)
	}
}

func TestInstallationRequestRejectsNonCanonicalEnvelope(t *testing.T) {
	for _, request := range []string{
		"SLKI2." + strings.TrimPrefix(androidInstallationRequest, "SLKI1."),
		strings.Replace(androidInstallationRequest, "SLKI1.", "SLKI1:", 1),
		androidInstallationRequest + "=",
		androidInstallationRequest[:len(androidInstallationRequest)-1] + "+",
		androidInstallationRequest[:len(androidInstallationRequest)-1],
		androidInstallationRequest + "A",
		"",
		strings.Repeat("A", 1024),
	} {
		if _, err := parseInstallationRequest(request); err == nil {
			t.Fatalf("non-canonical installation request was accepted: %q", request)
		}
	}
}

func TestInstallationRequestRejectsNonCanonicalDecodedIdentity(t *testing.T) {
	nonCanonicalNonce := androidDeviceNonce[:len(androidDeviceNonce)-1] + "9"
	for _, body := range []string{
		fmt.Sprintf(`{"deviceNonce":%q,"deviceId":%q}`, androidDeviceNonce, androidDeviceID),
		fmt.Sprintf(`{"deviceId":%q,"deviceNonce":%q}`, strings.Replace(androidDeviceID, "00000000", "AAAAAAAA", 1), androidDeviceNonce),
		fmt.Sprintf(`{"deviceId":%q,"deviceNonce":%q}`, androidDeviceID, nonCanonicalNonce),
		fmt.Sprintf(`{"deviceId":%q,"deviceNxxxx":%q}`, androidDeviceID, androidDeviceNonce),
		fmt.Sprintf(`{"deviceId":%q,"deviceNonce":%q`, androidDeviceID, androidDeviceNonce),
	} {
		request := "SLKI1." + base64.RawURLEncoding.EncodeToString([]byte(body))
		if _, err := parseInstallationRequest(request); err == nil {
			t.Fatalf("non-canonical decoded identity was accepted: %s", body)
		}
	}
}

func TestProvisionInputRejectsFormerPublicIdentityFieldsAndMalformedRequest(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	base := validProvisionPayload(
		"family-access-0123456789abcdef0123456789", androidInstallationRequest, 15, 1,
	)
	withFormerIdentityFields := strings.Replace(
		base, `"deviceName":`,
		`"deviceId":"00000000-0000-4000-8000-000000000201","deviceNonce":"`+androidDeviceNonce+`","deviceName":`, 1,
	)
	for _, payload := range []string{
		withFormerIdentityFields,
		strings.Replace(base, androidInstallationRequest, "short-request", 1),
		strings.Replace(base, `"activationTtlMinutes":15`, `"activationTtlMinutes":0`, 1),
		strings.Replace(base, `"activationTtlMinutes":15`, `"activationTtlMinutes":31`, 1),
		`{} {}`,
	} {
		if _, err := prepareProvisioning(strings.NewReader(payload), bytes.NewReader(make([]byte, 64)), now); err == nil {
			t.Fatalf("unsafe provisioning input was accepted: %s", payload)
		}
	}
}

func TestProvisionInputCanonicalizesIdentityAndEnforcesRevisionAndRecipients(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	payload := validProvisionPayload(
		"family-access-0123456789abcdef0123456789", androidInstallationRequest,
		30, store.MaxCredentialRevision,
	)
	payload = strings.ReplaceAll(payload, "00000000-0000-4000-8000-000000000101", "AAAAAAAA-0000-4000-8000-000000000101")
	payload = strings.ReplaceAll(payload, "00000000-0000-4000-8000-000000000001", "BBBBBBBB-0000-4000-8000-000000000001")
	payload = strings.ReplaceAll(payload, "00000000-0000-4000-8000-000000000201", "CCCCCCCC-0000-4000-8000-000000000201")
	payload = strings.ReplaceAll(payload, "00000000-0000-4000-8000-000000000301", "DDDDDDDD-0000-4000-8000-000000000301")
	prepared, err := prepareProvisioning(strings.NewReader(payload), bytes.NewReader(make([]byte, 64)), now)
	if err != nil {
		t.Fatal(err)
	}
	for name, value := range map[string]string{
		"household":    prepared.Plan.Identity.HouseholdID,
		"patient":      prepared.Plan.Identity.PatientID,
		"device":       prepared.Plan.Identity.DeviceID,
		"familyAccess": prepared.Plan.Identity.FamilySessionID,
	} {
		if value != strings.ToLower(value) {
			t.Fatalf("%s UUID is not canonical lowercase: %q", name, value)
		}
	}
	if !prepared.Plan.Activation.ExpiresAt.Equal(now.Add(store.MaxDeviceActivationLifetime)) {
		t.Fatalf("maximum activation lifetime mismatch: %v", prepared.Plan.Activation.ExpiresAt)
	}

	unsafeRevision := validProvisionPayload(
		"family-access-0123456789abcdef0123456789", androidInstallationRequest,
		15, store.MaxCredentialRevision+1,
	)
	if _, err := prepareProvisioning(strings.NewReader(unsafeRevision), bytes.NewReader(make([]byte, 64)), now); err == nil {
		t.Fatal("CLI accepted credentialRevision outside JSON safe-integer range")
	}
	withoutRecipients := strings.Replace(payload, `"telegramChatIds":["123456789"]`, `"telegramChatIds":[]`, 1)
	if _, err := prepareProvisioning(strings.NewReader(withoutRecipients), bytes.NewReader(make([]byte, 64)), now); err == nil {
		t.Fatal("CLI accepted production provisioning without Telegram recipients")
	}
}

func TestProvisionOutputContainsOnlyOneTimeCodeAndNonSecretMetadata(t *testing.T) {
	prepared := preparedProvisioning{
		Plan: store.DeviceActivationProvisioning{
			Identity: store.BootstrapIdentity{
				HouseholdID: "00000000-0000-4000-8000-000000000101",
				PatientID:   "00000000-0000-4000-8000-000000000001",
				DeviceID:    "00000000-0000-4000-8000-000000000201",
			},
			Activation: store.DeviceActivationCredential{ExpiresAt: time.Date(2026, 8, 2, 12, 15, 0, 0, time.UTC)},
		},
		ActivationCode: "SLK1-0000-0000-0000-0000-0000-0000-0000-0000",
	}
	var output bytes.Buffer
	if err := writeProvisionOutput(&output, prepared); err != nil {
		t.Fatal(err)
	}
	var body map[string]any
	if err := json.Unmarshal(output.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body) != 5 || body["activationCode"] != prepared.ActivationCode ||
		body["deviceId"] != prepared.Plan.Identity.DeviceID || body["patientId"] != prepared.Plan.Identity.PatientID ||
		body["householdId"] != prepared.Plan.Identity.HouseholdID || body["expiresAt"] == nil {
		t.Fatalf("unexpected CLI output: %#v", body)
	}
	for _, forbidden := range []string{"deviceToken", "familySessionToken", "deviceNonce"} {
		if strings.Contains(output.String(), forbidden) {
			t.Fatalf("CLI output exposed forbidden field %q", forbidden)
		}
	}
}

func TestProvisioningFailsClosedWhenRandomSourceIsIncomplete(t *testing.T) {
	_, err := prepareProvisioning(
		strings.NewReader(validProvisionPayload(
			"family-access-0123456789abcdef0123456789", androidInstallationRequest, 15, 1,
		)),
		io.LimitReader(strings.NewReader("short"), 5),
		time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC),
	)
	if err == nil {
		t.Fatal("CLI created an activation without sufficient randomness")
	}
}

func validProvisionPayload(familyAccess, installationRequest string, ttlMinutes, revision int64) string {
	return fmt.Sprintf(`{
		"householdId":"00000000-0000-4000-8000-000000000101",
		"householdName":"Семья",
		"patientId":"00000000-0000-4000-8000-000000000001",
		"patientName":"  Мама  ",
		"deviceName":"Samsung",
		"installationRequest":%q,
		"backendBindingId":"backend-binding-1",
		"credentialId":"credential-1",
		"credentialRevision":%d,
		"familySessionId":"00000000-0000-4000-8000-000000000301",
		"familySessionToken":%q,
		"activationTtlMinutes":%d,
		"telegramChatIds":["123456789"]
	}`, installationRequest, revision, familyAccess, ttlMinutes)
}

const (
	androidDeviceID            = "00000000-0000-4000-8000-000000000201"
	androidDeviceNonce         = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
	androidInstallationRequest = "SLKI1." +
		"eyJkZXZpY2VJZCI6IjAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDIwMSIsImRldmljZU5vbmNlIjoiQUFFQ0F3UUZCZ2NJQ1FvTERBME9EeEFSRWhNVUZSWVhHQmthR3h3ZEhoOCJ9"
)
