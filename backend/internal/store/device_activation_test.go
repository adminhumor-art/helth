package store

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

func TestMemoryProvisionsPendingDeviceAndConsumesActivationExactlyOnce(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	values := NewMemory()
	plan := testDeviceActivationProvisioning(now)
	if err := values.ProvisionDeviceActivation(context.Background(), plan); err != nil {
		t.Fatal(err)
	}
	if _, err := values.ResolveActiveDevice(context.Background(), HashAccessToken("not-issued"), now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("pending device authenticated before activation: %v", err)
	}
	if err := values.ValidateProductionAccess(context.Background(), now); err != nil {
		t.Fatalf("pending device with live activation failed startup readiness: %v", err)
	}

	deviceTokenHash := HashAccessToken("issued-device-token")
	request := DeviceActivationConsume{
		CodeHash: plan.Activation.CodeHash, DeviceID: plan.Identity.DeviceID,
		DeviceNonceHash: plan.Activation.DeviceNonceHash,
		DeviceTokenHash: deviceTokenHash, At: now.Add(time.Minute),
	}
	access, err := values.ConsumeDeviceActivation(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	if access.ID != plan.Identity.DeviceID || access.PatientID != plan.Identity.PatientID ||
		access.BackendBindingID != plan.Identity.BackendBindingID ||
		access.CredentialID != plan.Identity.CredentialID ||
		access.CredentialRevision != plan.Identity.CredentialRevision {
		t.Fatalf("activation returned wrong device binding: %#v", access)
	}
	resolved, err := values.ResolveActiveDevice(context.Background(), deviceTokenHash, now.Add(2*time.Minute))
	if err != nil || resolved != access {
		t.Fatalf("issued device token did not resolve exactly: access=%#v err=%v", resolved, err)
	}
	if _, err := values.ConsumeDeviceActivation(context.Background(), request); !errors.Is(err, ErrNotFound) {
		t.Fatalf("consumed activation code was reusable: %v", err)
	}
	if _, err := values.ResolveActiveDevice(context.Background(), HashAccessToken("second-token"), now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("repeated activation replaced the issued token: %v", err)
	}
}

func TestMemoryActivationIdentityMismatchDoesNotBurnCode(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	for _, mutate := range []func(*DeviceActivationConsume){
		func(request *DeviceActivationConsume) { request.DeviceID = "00000000-0000-4000-8000-000000000299" },
		func(request *DeviceActivationConsume) {
			request.DeviceNonceHash = HashAccessToken("wrong-device-nonce")
		},
	} {
		values := NewMemory()
		plan := testDeviceActivationProvisioning(now)
		if err := values.ProvisionDeviceActivation(context.Background(), plan); err != nil {
			t.Fatal(err)
		}
		wrong := DeviceActivationConsume{
			CodeHash: plan.Activation.CodeHash, DeviceID: plan.Identity.DeviceID,
			DeviceNonceHash: plan.Activation.DeviceNonceHash,
			DeviceTokenHash: HashAccessToken("wrong-attempt-token"), At: now.Add(time.Minute),
		}
		mutate(&wrong)
		if _, err := values.ConsumeDeviceActivation(context.Background(), wrong); !errors.Is(err, ErrNotFound) {
			t.Fatalf("mismatched activation identity did not fail closed: %v", err)
		}
		correct := DeviceActivationConsume{
			CodeHash: plan.Activation.CodeHash, DeviceID: plan.Identity.DeviceID,
			DeviceNonceHash: plan.Activation.DeviceNonceHash,
			DeviceTokenHash: HashAccessToken("correct-attempt-token"), At: now.Add(2 * time.Minute),
		}
		if _, err := values.ConsumeDeviceActivation(context.Background(), correct); err != nil {
			t.Fatalf("identity mismatch burned the one-time code: %v", err)
		}
	}
}

func TestMemoryExpiredActivationNeverIssuesDeviceCredential(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	values := NewMemory()
	plan := testDeviceActivationProvisioning(now)
	if err := values.ProvisionDeviceActivation(context.Background(), plan); err != nil {
		t.Fatal(err)
	}
	request := DeviceActivationConsume{
		CodeHash: plan.Activation.CodeHash, DeviceID: plan.Identity.DeviceID,
		DeviceNonceHash: plan.Activation.DeviceNonceHash,
		DeviceTokenHash: HashAccessToken("expired-attempt-token"), At: plan.Activation.ExpiresAt,
	}
	if _, err := values.ConsumeDeviceActivation(context.Background(), request); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired activation issued a token: %v", err)
	}
	if err := values.ValidateProductionAccess(context.Background(), plan.Activation.ExpiresAt); !errors.Is(err, ErrAccessNotProvisioned) {
		t.Fatalf("expired pending activation passed readiness: %v", err)
	}
}

func TestMemoryConcurrentActivationHasExactlyOneWinner(t *testing.T) {
	now := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	values := NewMemory()
	plan := testDeviceActivationProvisioning(now)
	if err := values.ProvisionDeviceActivation(context.Background(), plan); err != nil {
		t.Fatal(err)
	}

	type result struct {
		tokenHash []byte
		err       error
	}
	start := make(chan struct{})
	results := make(chan result, 2)
	var workers sync.WaitGroup
	for _, rawToken := range []string{"concurrent-device-token-a", "concurrent-device-token-b"} {
		workers.Add(1)
		go func(rawToken string) {
			defer workers.Done()
			<-start
			tokenHash := HashAccessToken(rawToken)
			_, err := values.ConsumeDeviceActivation(context.Background(), DeviceActivationConsume{
				CodeHash: plan.Activation.CodeHash, DeviceID: plan.Identity.DeviceID,
				DeviceNonceHash: plan.Activation.DeviceNonceHash,
				DeviceTokenHash: tokenHash, At: now.Add(time.Minute),
			})
			results <- result{tokenHash: tokenHash, err: err}
		}(rawToken)
	}
	close(start)
	workers.Wait()
	close(results)

	winners := 0
	for outcome := range results {
		if outcome.err == nil {
			winners++
			if _, err := values.ResolveActiveDevice(context.Background(), outcome.tokenHash, now); err != nil {
				t.Fatalf("winning device token was not committed: %v", err)
			}
		} else if !errors.Is(outcome.err, ErrNotFound) {
			t.Fatalf("losing concurrent consume returned unexpected error: %v", outcome.err)
		}
	}
	if winners != 1 {
		t.Fatalf("concurrent activation winners=%d want exactly 1", winners)
	}
}

func testDeviceActivationProvisioning(now time.Time) DeviceActivationProvisioning {
	return DeviceActivationProvisioning{
		Identity: BootstrapIdentity{
			HouseholdID:   "00000000-0000-4000-8000-000000000101",
			HouseholdName: "Семья",
			PatientID:     "00000000-0000-4000-8000-000000000001", PatientName: "Мама",
			DeviceID: "00000000-0000-4000-8000-000000000201", DeviceName: "Samsung",
			BackendBindingID: "backend-binding-1", CredentialID: "credential-1", CredentialRevision: 1,
			FamilySessionID:    "00000000-0000-4000-8000-000000000301",
			FamilyTokenHash:    HashAccessToken("family-access-token"),
			TelegramRecipients: []string{"123456789"},
		},
		Activation: DeviceActivationCredential{
			ID:       "00000000-0000-4000-8000-000000000401",
			CodeHash: HashAccessToken("activation-code"), DeviceNonceHash: HashAccessToken("device-nonce"),
			CreatedAt: now, ExpiresAt: now.Add(15 * time.Minute),
		},
	}
}
