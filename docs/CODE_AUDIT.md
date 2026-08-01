# Аудит текущего кода

Обновлено: 1 августа 2026 года.

## Вывод

Переписывать весь продукт заново не требуется. Android, backend и сайт остаются
модульной продуктовой основой. Сенсорный тракт строится отдельно вокруг
официального нативного ядра и допускается к продукту только через replay и
физические проверки.

Наличие кода и зелёных синтетических тестов не означает медицинскую готовность.
До физического допуска ядро работает только в диагностическом режиме и не может
создать значение для пользователя.

## Оставить

| Часть | Почему остаётся | Следующий барьер |
| --- | --- | --- |
| Compose-экран | Большое значение, затем график; есть свежесть и настройки | Accessibility и проверка на Samsung |
| `SensorDriver` | Изолирует источники данных | Подключить только физически допущенный профиль |
| Симулятор | TDD интерфейса, тревог и пропусков | Больше сценариев restart/permission |
| Чистая Room `v1` | Атомарное stateful-хранилище и локальная очередь | Runtime instrumentation, process-kill и полный replay |
| Foreground service | Основа непрерывного мониторинга | Реальный configured path, Doze и process recovery |
| Локальная политика тревог | Независима от интернета; есть повтор и подтверждение | DND, Doze и физическая громкость |
| Модульный Go backend | Соответствует текущему масштабу | Настройки пациента, auth и deploy |
| PostgreSQL outbox | Не теряет тревогу вместе с measurement | Настоящий Telegram end-to-end |
| Семейный web UI | Уже показывает основной порядок и разрыв | Авторизация и подключение API |

## Уже исправлено

- График Android и сайта строится по реальному времени и разрывается при
  отсутствии данных.
- Статус Android больше не выводит «свежие данные» только по наличию числа.
- Пороговые настройки сохраняются и перезагружают локальную политику.
- Демо не попадает в Room, uploader, продуктовый endpoint или удалённые тревоги.
- Backend атомарно сохраняет measurement, alert changes и Telegram outbox.
- Backend восстанавливает monitoring baseline и signal-loss после перезапуска.
- Только последнее `VALID` измерение влияет на snapshot и signal-loss; свежесть
  берётся по самому раннему из времени датчика, телефона и сервера.
- Прогрев и degraded не освежают наблюдение и не закрывают glucose- или
  signal-loss тревогу.
- Неподтверждённая Android-тревога повторяется каждые две минуты; подтверждение
  приглушает только текущий эпизод.
- Проект ещё не выпускался. Room `v1` и PostgreSQL `schema/initial.sql` являются
  первыми и единственными текущими схемами.
- Golden fixture publication разрешена только для одного канонического
  синтетического файла с закреплённым SHA-256.
- Recovery pending ingress проверяет семейство датчика до replay и не вызывает
  алгоритм при identity mismatch.
- GATT generation, identity и ownership сведены в один атомарный registry;
  callback-before-return, stale callback и close проверены fake-GATT тестами,
  отклонённый транспорт освобождается ровно один раз глобально. Cross-lease
  stealing и rebind во время release запрещены identity-owner и weak tombstone.
- Backend требует явный `APP_ENV` и разные device/family token во всех режимах.
- `sequence` ограничен единым JSON-safe диапазоном в домене, начальной схеме,
  OpenAPI и web; большее значение не доходит до хранения.
- Web API boundary fail-closed сверяет trusted patient, свежесть, качество,
  часы и гонку snapshot/history; полный TypeScript-check включён в проверенный
  CI-шаблон, ожидающий активации.

## Обязательно довести

| Часть | Фактический остаток |
| --- | --- |
| GS1/GS1Sb | ARM JNI, private capture и полный датчик ещё не проверены |
| BLE identity | Name suffix остаётся кандидатом, а не доказательством exact MAC |
| GS3 | Требует отдельного транспорта, account binding и физического допуска |
| Room restart | Двукратный reopen компилируется; Emulator установлен, но ARM image ожидает принятия владельцем отдельной лицензии; нет device runtime, process-kill и полного replay |
| Фоновая работа | Fake-GATT lifecycle готов; in-flight platform call может пересечь stop, нет реального configured stream, Samsung/Doze/process-kill испытаний |
| Тревога Android | Программный повтор/подтверждение готовы; нужны DND, Doze, громкость и ночной тест |
| Виджет | Политика скрывает demo/stale/clock-mismatch/non-VALID; нет строгой гарантии watchdog после гибели процесса |
| Настройки | Локальные и серверные пороги пока не синхронизированы |
| Backend auth | Device/family token разделены, но это ещё не семейные аккаунты, сессии и роли |
| Telegram | Нет настоящего bot token/chat и сетевой end-to-end проверки; claim/lease/retry уже покрыты тестами |
| Сайт | Typed boundary и JSON-safe sequence готовы, но нет server-side BFF/session; family token нельзя отдавать браузеру |

## Диагностический карантин

GS1/GS1Sb runtime уже включает проверенный порядок команд, двойной парсер,
stateful-алгоритм, append-only ingress, атомарный checkpoint, GATT deadlines,
bounded queue и restart recovery pending-пакетов. Onboarding создаёт только
`PENDING_DIAGNOSTIC`.

Результат всегда имеет `publishable=false` и `alarmEligible=false`. Он не может
попасть в `GlucoseReading`, график, виджет, тревогу, backend или Telegram.

Следующие барьеры: реальный ARM-smoke, private replay обоих профилей, runtime
Room/process-kill и полный жизненный цикл отдельного датчика. GS3 проверяется
отдельным трактом.

## Не требуется сейчас

- часы, звонки и SMS;
- Google Play;
- микросервисы и Kubernetes;
- остальные производители CGM до GS1/GS1Sb/GS3;
- публичный доступ к семейным данным без авторизации.

## Контроль версий

`glucose-monitor` — отдельный Git-репозиторий. Основной remote:
`https://github.com/adminhumor-art/helth.git`. Секреты не входят в remote URL,
исходники, документацию или commit.
