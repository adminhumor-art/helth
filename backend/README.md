# Backend «Сладкой»

Backend поддерживает несколько семей, пациентов и телефонов. Авторизация не
связана с одним глобальным `PATIENT_ID`:

- device token хешируется SHA-256 и находит активное устройство и его пациента;
- provisioned family access принимается только server-side exchange endpoint;
- браузер получает отдельную короткоживущую непрозрачную cookie
  `family_session`, которая отдельно находит семью и даёт доступ только к
  пациентам этой семьи;
- `patientId` из тела измерения не принимается — backend всегда подставляет
  пациента найденного устройства;
- каждое измерение обязано передать точный `deviceId`, `backendBindingId`,
  `credentialId` и `credentialRevision`; tuple сверяется повторно внутри
  транзакции, поэтому устаревший outbox не может попасть другому пациенту;
- Telegram-получатели берутся из семьи пациента, поэтому тревога одной семьи
  не может уйти другой;
- сырые токены не сохраняются в PostgreSQL и не пишутся в логи.

Все UUID при создании доступа приводятся к канонической строчной записи.
`credentialRevision` ограничен диапазоном `1..9007199254740991` одинаково в
HTTP, provisioning, памяти, PostgreSQL и OpenAPI.

## HTTP authentication boundary

`POST /v1/device/measurements` использует только
`Authorization: Bearer <device token>`.

`POST /v1/family/session` — единственная точка обмена заранее provisioned
family access на браузерную сессию. Доверенный same-origin BFF передаёт family
access через `Authorization: Bearer` и точный `Origin` из
`FAMILY_WEB_ORIGINS`. Backend возвращает:

- новую случайную `family_session` cookie с `HttpOnly`, `Secure`,
  `SameSite=Strict`, `Path=/` и ограниченным `Max-Age`;
- JSON `csrfToken` и `expiresAt`; BFF хранит CSRF server-side и не выдаёт ни
  provisioned family access, ни session cookie браузерному JavaScript.

Provisioned family access не принимается как cookie. В PostgreSQL сохраняются
только SHA-256 digests family access, browser session и CSRF token.
Browser-session дополнительно привязана к исходному provisioned доступу: его
истечение или отзыв немедленно делает выданную сессию недействительной.

Семейные snapshot и history endpoints принимают только `family_session`.
`POST /v1/alerts/{alertId}/acknowledge` дополнительно требует точный разрешённый
`Origin` и `X-CSRF-Token`, привязанный к этой же сессии. Отсутствующий,
чужой или повторяющийся Origin, а также CSRF от другой сессии отклоняются до
изменения тревоги.

`FAMILY_WEB_ORIGINS` обязателен. В production разрешены только точные HTTPS
origin без path/query/fragment; в development/test HTTP допускается только для
loopback (`localhost`, `127.0.0.1`, `::1`). Wildcard не поддерживается.

## Development

Для временного bootstrap задайте все значения из `.env.example`. Токены должны
быть разными и содержать не менее 32 символов. Это явное создание одной тестовой
семьи; скрытых стандартных токенов нет.

## Production provisioning

Production не принимает startup-bootstrap значения (`DEVICE_TOKEN`,
`FAMILY_SESSION_TOKEN`, `PATIENT_ID`, `BACKEND_BINDING_ID`, `CREDENTIAL_ID`,
`CREDENTIAL_REVISION`, `TELEGRAM_CHAT_IDS`). Сначала выполните одноразовое
provisioning через `cmd/provision-access`, передав JSON в stdin. Секреты нельзя
передавать аргументами командной строки или добавлять в Git.

До запуска команды приложение создаёт и хранит в защищённом хранилище два
значения: постоянный для этой установки `deviceId` (UUID) и `deviceNonce`
(32 случайных байта в canonical base64url без `=`). Экран привязки объединяет
их в одно непрозрачное `installationRequest`; администратор передаёт в
provisioning только эту строку, не извлекая из неё исходные значения:

```json
{
  "householdId": "00000000-0000-4000-8000-000000000101",
  "householdName": "Семья",
  "patientId": "00000000-0000-4000-8000-000000000001",
  "patientName": "Мама",
  "deviceName": "Samsung",
  "installationRequest": "SLKI1.eyJkZXZpY2VJZCI6IjAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDIwMSIsImRldmljZU5vbmNlIjoiQUFFQ0F3UUZCZ2NJQ1FvTERBME9EeEFSRWhNVUZSWVhHQmthR3h3ZEhoOCJ9",
  "backendBindingId": "backend-binding-1",
  "credentialId": "device-credential-1",
  "credentialRevision": 1,
  "familySessionId": "00000000-0000-4000-8000-000000000301",
  "familySessionToken": "другой-случайный-family-access-минимум-32-символа",
  "activationTtlMinutes": 15,
  "telegramChatIds": ["123456789"]
}
```

`installationRequest` имеет строгий формат: префикс `SLKI1.` и ровно 148
символов canonical base64url без padding (154 символа целиком). Команда
принимает только канонический запрос, созданный приложением.

Команда использует `DATABASE_URL`, атомарно создаёт семью, пациента, ожидающее
устройство и одноразовую активацию. В базе сохраняются только SHA-256 digests
`familySessionToken`, кода активации и `deviceNonce`. После успешной транзакции
команда один раз выводит код активации, время его истечения и идентификаторы:

```sh
go run ./cmd/provision-access < /secure/path/provision.json
```

```json
{
  "activationCode": "SLK1-0000-0000-0000-0000-0000-0000-0000-0000",
  "expiresAt": "2026-08-02T12:15:00Z",
  "householdId": "00000000-0000-4000-8000-000000000101",
  "patientId": "00000000-0000-4000-8000-000000000001",
  "deviceId": "00000000-0000-4000-8000-000000000201"
}
```

Приложение отправляет код вместе со своими исходными `deviceId` и
`deviceNonce` на `POST /v1/device/provision`:

```json
{
  "activationCode": "SLK1-0000-0000-0000-0000-0000-0000-0000-0000",
  "deviceId": "00000000-0000-4000-8000-000000000201",
  "deviceNonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
}
```

При успехе backend атомарно погашает код, создаёт новый 256-битный device bearer
и возвращает его только один раз:

```json
{
  "deviceToken": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
  "apiOrigin": "https://api.example.com",
  "deviceId": "00000000-0000-4000-8000-000000000201",
  "patientId": "00000000-0000-4000-8000-000000000001",
  "backendBindingId": "backend-binding-1",
  "credentialId": "device-credential-1",
  "credentialRevision": 1
}
```

Код действует 1–30 минут (по умолчанию 15), подходит только указанным
`deviceId` и `deviceNonce` и не даёт семейного web-доступа. Неизвестный,
истёкший, уже использованный код или неверная пара устройства получают
одинаковый `401`; неверная попытка не сжигает правильный код. Если телефон
потерял успешный ответ `201`, тот же код намеренно нельзя использовать повторно:
нужно создать новую активацию, чтобы исключить выдачу двух credential.

`DEVICE_API_ORIGIN` обязан содержать точный публичный origin API. В production
разрешён только HTTPS без path/query/fragment; HTTP допускается лишь в
development/test и только для loopback. Приложение сохраняет `deviceToken` и
полученную привязку в защищённом хранилище и использует их для последующей
отправки измерений.

Файл с исходным family access должен храниться вне репозитория с правами только
для владельца. Provisioned family access передаётся только конфигурации
доверенного web BFF, а не браузерному коду. При старте production-сервер
проверяет, что каждый пациент имеет активное устройство либо живую ожидающую
активацию, а его семья — активный family access и хотя бы одного непустого
Telegram-получателя; истёкшая активация готовностью не считается. При неполной
схеме доступа запуск останавливается. Production-provisioning не принимает
пустые `patientName` и `telegramChatIds`. Production всегда требует настроенный
`TELEGRAM_BOT_TOKEN`.

Имя пациента нормализуется в одну строку и ограничивается 80 символами. Оно
берётся из `patients.display_name` и включается в каждое Telegram-сообщение,
чтобы тревоги разных людей нельзя было перепутать. Скрытого глобального списка
получателей нет: адресаты принадлежат конкретной семье.

Проект использует одну актуальную чистую initial schema v1. Production-база
создаётся пустой из этой схемы до provisioning.

`devices.last_seen_at` обновляется только в той же транзакции, в которой backend
успешно принял измерение (включая точный повтор). Простая проверка токена,
повреждённый payload или конфликт события это поле не меняют. Медицинская
свежесть по-прежнему определяется временами измерения/телефона/приёма, а не
`last_seen_at`. Это поле монотонно и не двигается назад при поздней доставке
более старого принятого события.

Provisioning само по себе не включает контроль потери сигнала. Единственная
запись, активирующая monitoring, создаётся атомарно с первым принятым измерением
качества `valid`. Перезапуск backend восстанавливает существующий timer и не
создаёт новый; пациент без первого valid-измерения не получает ложную тревогу
потери сигнала.

Проверка потери сигнала выполняется для пациентов с ограниченным параллелизмом
и отдельным timeout на пациента. Общий запрос списка пациентов ограничен пятью
секундами, а один цикл Telegram delivery — 90 секундами. Блокировка одного
DB-вызова не останавливает последующие циклы навсегда.
