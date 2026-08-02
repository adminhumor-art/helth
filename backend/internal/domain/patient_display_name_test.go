package domain

import (
	"strings"
	"testing"
	"unicode"
	"unicode/utf8"
)

func TestNormalizePatientDisplayNameIsSingleLineValidAndBounded(t *testing.T) {
	if got := NormalizePatientDisplayName("  Мама\n\t Иванова  "); got != "Мама Иванова" {
		t.Fatalf("display name whitespace was not normalized: %q", got)
	}
	got := NormalizePatientDisplayName("Ма\x00ма\u202e " + strings.Repeat("Я", MaxPatientDisplayNameRunes))
	if !utf8.ValidString(got) {
		t.Fatalf("display name is not valid UTF-8: %q", got)
	}
	if utf8.RuneCountInString(got) > MaxPatientDisplayNameRunes {
		t.Fatalf("display name contains %d runes, max %d", utf8.RuneCountInString(got), MaxPatientDisplayNameRunes)
	}
	for _, value := range got {
		if unicode.IsControl(value) || unicode.In(value, unicode.Cf, unicode.Zl, unicode.Zp) {
			t.Fatalf("display name retained unsafe rune %U", value)
		}
	}
	if got := NormalizePatientDisplayName("\x00\u202e\n"); got != "" {
		t.Fatalf("control-only display name became %q", got)
	}
}
