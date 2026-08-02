package httpapi

import (
	"os"
	"strings"
	"testing"
)

func TestProductOpenAPIExcludesSimulatorAndRequiresSequence(t *testing.T) {
	encoded, err := os.ReadFile("../../../contracts/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	contract := string(encoded)
	if strings.Contains(contract, "enum: [sibionics_gs1, sibionics_gs1sb, sibionics_gs3, simulator]") {
		t.Fatal("simulator must not appear in the product measurement ingest enum")
	}
	requiredSequence := "        - quality\n        - sequence\n"
	if !strings.Contains(contract, requiredSequence) {
		t.Fatal("measurement sequence must remain required in the product contract")
	}
	sequenceRange := "        sequence:\n          type: integer\n          minimum: 0\n          maximum: 9007199254740991\n"
	if !strings.Contains(contract, sequenceRange) {
		t.Fatal("measurement sequence must remain a JSON safe integer across Go and JavaScript")
	}
}

func TestProductOpenAPISeparatesDeviceBearerFromCookieOnlyFamilySession(t *testing.T) {
	encoded, err := os.ReadFile("../../../contracts/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	contract := string(encoded)

	deviceScheme := "    deviceToken:\n" +
		"      type: http\n" +
		"      scheme: bearer\n"
	if !strings.Contains(contract, deviceScheme) {
		t.Fatal("device ingest must remain protected by the documented Bearer scheme")
	}
	familyScheme := "    familySession:\n" +
		"      type: apiKey\n" +
		"      in: cookie\n" +
		"      name: family_session\n" +
		"      description: Server-issued session cookie; must use HttpOnly, Secure and SameSite=Strict attributes. Authorization Bearer is not accepted.\n"
	if !strings.Contains(contract, familyScheme) {
		t.Fatal("family API must document the cookie-only HttpOnly session boundary")
	}
}
