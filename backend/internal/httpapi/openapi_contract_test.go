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
}
