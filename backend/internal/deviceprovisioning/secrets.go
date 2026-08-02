package deviceprovisioning

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"io"
	"strings"
)

const (
	ActivationCodeEntropyBytes = 20
	OpaqueTokenEntropyBytes    = 32
	activationCodePrefix       = "SLK1"
	activationAlphabet         = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
)

func GenerateActivationCode(random io.Reader) (string, error) {
	raw := make([]byte, ActivationCodeEntropyBytes)
	if _, err := io.ReadFull(random, raw); err != nil {
		return "", err
	}
	encoded := encodeActivationEntropy(raw)
	var result strings.Builder
	result.Grow(len(activationCodePrefix) + 1 + len(encoded) + 7)
	result.WriteString(activationCodePrefix)
	for offset := 0; offset < len(encoded); offset += 4 {
		result.WriteByte('-')
		result.WriteString(encoded[offset : offset+4])
	}
	return result.String(), nil
}

func ValidActivationCode(code string) bool {
	if len(code) != len(activationCodePrefix)+1+32+7 || !strings.HasPrefix(code, activationCodePrefix+"-") {
		return false
	}
	parts := strings.Split(code, "-")
	if len(parts) != 9 || parts[0] != activationCodePrefix {
		return false
	}
	for _, part := range parts[1:] {
		if len(part) != 4 {
			return false
		}
		for _, symbol := range part {
			if !strings.ContainsRune(activationAlphabet, symbol) {
				return false
			}
		}
	}
	return true
}

func GenerateOpaqueToken(random io.Reader) (string, error) {
	raw := make([]byte, OpaqueTokenEntropyBytes)
	if _, err := io.ReadFull(random, raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func ValidDeviceNonce(value string) bool {
	if len(value) != base64.RawURLEncoding.EncodedLen(OpaqueTokenEntropyBytes) {
		return false
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	return err == nil && len(decoded) == OpaqueTokenEntropyBytes &&
		base64.RawURLEncoding.EncodeToString(decoded) == value
}

func GenerateUUID(random io.Reader) (string, error) {
	value := make([]byte, 16)
	if _, err := io.ReadFull(random, value); err != nil {
		return "", err
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(value)
	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" +
		encoded[16:20] + "-" + encoded[20:32], nil
}

func encodeActivationEntropy(raw []byte) string {
	if len(raw) != ActivationCodeEntropyBytes {
		panic(errors.New("activation entropy length invariant violated"))
	}
	result := make([]byte, 0, 32)
	var buffer uint32
	bits := 0
	for _, value := range raw {
		buffer = (buffer << 8) | uint32(value)
		bits += 8
		for bits >= 5 {
			bits -= 5
			result = append(result, activationAlphabet[(buffer>>bits)&31])
		}
	}
	return string(result)
}
