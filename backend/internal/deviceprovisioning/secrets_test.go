package deviceprovisioning

import (
	"bytes"
	"encoding/base64"
	"io"
	"strings"
	"testing"
)

func TestActivationCodeUsesTwentyRandomBytesAndExactHumanSafeFormat(t *testing.T) {
	random := bytes.NewReader(make([]byte, ActivationCodeEntropyBytes))
	code, err := GenerateActivationCode(random)
	if err != nil {
		t.Fatal(err)
	}
	if code != "SLK1-0000-0000-0000-0000-0000-0000-0000-0000" {
		t.Fatalf("unexpected deterministic activation code %q", code)
	}
	if !ValidActivationCode(code) {
		t.Fatal("generated activation code does not satisfy its own exact contract")
	}
	for _, changed := range []string{
		strings.ToLower(code),
		" " + code,
		strings.Replace(code, "-", "", 1),
		strings.Replace(code, "0", "O", 1),
	} {
		if ValidActivationCode(changed) {
			t.Fatalf("non-exact or ambiguous activation code was accepted: %q", changed)
		}
	}
}

func TestActivationCodeGenerationFailsClosedOnInsufficientRandomness(t *testing.T) {
	if _, err := GenerateActivationCode(io.LimitReader(strings.NewReader("short"), 5)); err == nil {
		t.Fatal("activation code was generated without the required entropy")
	}
}

func TestDeviceNonceAndBearerTokenUseCanonical256BitBase64URL(t *testing.T) {
	raw := make([]byte, OpaqueTokenEntropyBytes)
	for index := range raw {
		raw[index] = byte(index)
	}
	expected := base64.RawURLEncoding.EncodeToString(raw)
	token, err := GenerateOpaqueToken(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	if token != expected || len(token) != 43 || !ValidDeviceNonce(token) {
		t.Fatalf("unexpected opaque token contract: token=%q", token)
	}
	for _, invalid := range []string{"", token + "=", " " + token, token[:42]} {
		if ValidDeviceNonce(invalid) {
			t.Fatalf("non-canonical device nonce was accepted: %q", invalid)
		}
	}
}
