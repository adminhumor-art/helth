package httpapi

import (
	"os"
	"strings"
	"testing"
)

func TestProductOpenAPIRequiresExactDeviceBindingAndMinimalAcceptance(t *testing.T) {
	encoded, err := os.ReadFile("../../../contracts/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	contract := string(encoded)
	inputStart := strings.Index(contract, "    MeasurementInput:\n")
	inputEnd := strings.Index(contract, "    MeasurementFields:\n")
	if inputStart < 0 || inputEnd <= inputStart {
		t.Fatal("measurement input schema boundaries are missing")
	}
	inputSchema := contract[inputStart:inputEnd]

	for _, required := range []string{
		"        - deviceId\n",
		"        - backendBindingId\n",
		"        - credentialId\n",
		"        - credentialRevision\n",
	} {
		if !strings.Contains(inputSchema, required) {
			t.Fatalf("device ingest contract is missing required identity field %q", strings.TrimSpace(required))
		}
	}
	if strings.Contains(inputSchema, "patientId") {
		t.Fatal("device payload must not choose its patient")
	}
	if !strings.Contains(inputSchema, "          maximum: 9007199254740991\n") {
		t.Fatal("credentialRevision must use the same JSON safe-integer maximum as runtime and PostgreSQL")
	}

	minimalAcceptance := "    IngestResult:\n" +
		"      type: object\n" +
		"      additionalProperties: false\n" +
		"      required: [accepted]\n" +
		"      properties:\n" +
		"        accepted:\n" +
		"          type: boolean\n" +
		"          const: true\n"
	if !strings.Contains(contract, minimalAcceptance) {
		t.Fatal("202 response must be exactly the one-field object {accepted:true}")
	}
	if strings.Contains(contract, "      required: [accepted, duplicate, serverTime]") {
		t.Fatal("device response exposes obsolete duplicate/serverTime fields")
	}
}

func TestProductOpenAPIDocumentsOneTimeDeviceProvisioningWithoutFamilyAccess(t *testing.T) {
	encoded, err := os.ReadFile("../../../contracts/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	contract := string(encoded)
	for _, required := range []string{
		"  /v1/device/provision:\n",
		"      operationId: provisionDevice\n",
		"              $ref: \"#/components/schemas/DeviceProvisionInput\"\n",
		"                $ref: \"#/components/schemas/DeviceProvisioned\"\n",
		"    DeviceProvisionInput:\n",
		"      required: [activationCode, deviceId, deviceNonce]\n",
		"          pattern: \"^SLK1-(?:[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{4}-){7}[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{4}$\"\n",
		"    DeviceProvisioned:\n",
		"      required: [deviceToken, apiOrigin, deviceId, patientId, backendBindingId, credentialId, credentialRevision]\n",
	} {
		if !strings.Contains(contract, required) {
			t.Fatalf("one-time device provisioning contract is missing %q", required)
		}
	}
	start := strings.Index(contract, "    DeviceProvisioned:\n")
	if start < 0 {
		t.Fatal("device provisioning response schema boundaries are missing")
	}
	end := strings.Index(contract[start:], "    MeasurementInput:\n")
	if end < 0 {
		t.Fatal("device provisioning response schema boundaries are missing")
	}
	responseSchema := contract[start : start+end]
	if strings.Contains(responseSchema, "familySession") || strings.Contains(responseSchema, "familyAccess") {
		t.Fatal("device provisioning response must never expose family access")
	}
}
