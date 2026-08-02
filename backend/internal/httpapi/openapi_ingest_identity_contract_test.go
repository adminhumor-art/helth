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
