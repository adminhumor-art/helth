package domain

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

const MaxPatientDisplayNameRunes = 80

func NormalizePatientDisplayName(value string) string {
	value = strings.ToValidUTF8(value, "")
	value = strings.Map(func(character rune) rune {
		if unicode.IsControl(character) || unicode.In(character, unicode.Cf, unicode.Zl, unicode.Zp) {
			return ' '
		}
		return character
	}, value)
	value = strings.Join(strings.Fields(value), " ")
	if utf8.RuneCountInString(value) <= MaxPatientDisplayNameRunes {
		return value
	}
	return strings.TrimSpace(string([]rune(value)[:MaxPatientDisplayNameRunes]))
}
