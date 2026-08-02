# Журнал разработки «Сладкой»

Обновлено: 2 августа 2026 года.

Проект ещё не выпускался. Первая и единственная текущая схема — Room `v1` и
PostgreSQL `schema/initial.sql`; обе сразу описывают актуальную модель.

## Стартовая продуктовая граница

Владелец зафиксировал:

- Android-приложение называется «Сладкая»;
- первый экран телефона и сайта показывает крупное значение глюкозы, а ниже —
  график;
- в Android нужны локальная тревога, настройки порогов и виджет;
- семье нужны закрытый сайт и Telegram-уведомления;
- часы, звонки, SMS и Google Play пока не входят в текущую итерацию;
- первые датчики — SiBionics GS1, GS1Sb и GS3;
- реальный результат нельзя выводить человеку до доказанного прохождения всего
  сенсорного тракта;
- финальные продуктовые решения принимает владелец.

Работа ведётся по TDD: воспроизводящая проверка, минимальный код, общий прогон и
только затем рефакторинг.

## Исследование сенсорного тракта

В отдельном исследовательском каталоге выполнен read-only разбор доступных
исходников и официальных Android-пакетов. Проверены международный пакет
`01.20.01.00` и китайский `02.14.00.00`: подписи, версии, состав native-библиотек,
JNI ABI, BLE UUID, формат команд и stateful-жизненный цикл алгоритма.

Подтверждённая схема GS1/GS1Sb:

1. Пользователь сканирует DataMatrix или вручную вводит восемь ASCII-букв/цифр.
2. Приложение ищет BLE-кандидата и не считает имя устройства доказанной
   физической идентичностью.
3. Каждый encrypted notification сначала сохраняется в append-only ingress.
4. Официальный transport datahandle и независимый parser должны выдать
   одинаковые raw current, temperature, sequence и время.
5. Stateful native algorithm получает проверенный raw sample.
6. Результат и новый бинарный checkpoint фиксируются атомарно.
7. Следующая точка не обрабатывается, пока сохранение checkpoint не подтверждено.

Нативное ядро вычисляет значение; Android-адаптер не заменяет его собственной
формулой. Регистрационный материал внутри native-протокола сохранён побайтно как
протокольная константа, не является пользовательским или deploy-секретом и не
попадает в логи.

GS3 отделён от GS1/GS1Sb. Текущая GS1-сессия отклоняет GS3 сразу при создании.
Неподтверждённая GS3 state machine и статический ключ удалены. Для GS3 нужен
собственный проверенный транспорт и физический replay.

## TDD сенсорного ядра GS1/GS1Sb

После падающих контрактных проверок реализованы:

- отдельные Android-модули native algorithm и native datahandle;
- контроль полного списка ABI, файлов и SHA-256 бинарников при сборке;
- точные Java/JNI-контексты и порядок `init → process → state → restore`;
- строгий восьмисимвольный case-preserved код без запасного идентификатора;
- официальный decoder sensitivity с разрешённым fallback только при точном
  специальном результате;
- двойная проверка transport datahandle и независимого parser;
- ограниченная последовательная очередь, GATT deadlines, bounded reconnect и
  terminal fail-closed;
- после падающих fake-GATT сценариев generation, identity, bind и close сведены
  в один атомарный registry: callback до возврата `connectGatt`, поздний
  callback, stop во время блокирующего disconnect и exactly-once release
  проверяются на JVM; устаревший callback не может отравить текущую попытку;
- глубокий ownership-review отдельными red-тестами воспроизвёл попытку передать
  один GATT между двумя lease и повторную привязку во время блокирующего release;
  глобальный identity-owner и GC-safe weak tombstone закрыли обе гонки, не
  смешивая разные, но равные по `equals` объекты;
- append-only ingress, outcomes, журнал отказов и restart recovery pending
  пакетов;
- атомарное сохранение raw sample, algorithm result и checkpoint;
- запрет регрессии checkpoint, скрытого drop и обгона неподтверждённой записи;
- диагностический onboarding с ручным кодом или DataMatrix и явным состоянием
  `PENDING_DIAGNOSTIC`;
- типизированная граница `DIAGNOSTIC_ONLY`: `publishable=false`,
  `alarmEligible=false`, продуктовый `GlucoseReading` не создаётся.

Golden/replay-контур проверяет точные encrypted bytes, raw samples, diagnostic
bits, trend, warning-коды, checkpoint, повторное открытие native context,
initialization mode, token source и sensitivity binding. В Git разрешён только
один канонический синтетический fixture с закреплённым SHA-256. Любая запись
физического датчика считается закрытым чувствительным evidence и исключена из
репозитория.

## Итерация готовности к первому международному или китайскому датчику

Сначала добавлены падающие проверки, затем закрыты найденные разрывы:

- международный и китайский рынок теперь проходят один диагностический
  onboarding; внутренний wire-профиль не показывается пользователю и
  определяется по точному ответу датчика;
- на всех production-boundary wire-профиль обязателен явно: скрытого V120 по
  умолчанию больше нет;
- V115 имеет собственный строгий codec, один стартовый запрос истории и дальше
  принимает sensor-driven notifications без нового запроса после commit;
- сохранена reference-compatible MTU-граница: без request/reassembly, один
  callback — один envelope; single-record V120/V115 закреплены тестами как
  20/19 байт, а actual MTU/размеры войдут в private capture первого Samsung;
- точное V115-время хранит receive-time/add-time/clamp provenance и допускает
  реальные равные или неминутные интервалы без искусственной коррекции;
- пустой корректный envelope считается только transport progress, завершает
  handshake и запускает silence-watchdog, но не создаёт показание;
- `NORMAL` и `FACTION` вызывают разные точные native entry point. Полная tuple
  калибровки сохраняется через result/checkpoint/Room и проверена при reopen для
  V115G и V116A;
- версия алгоритма стала обязательной реальной native metadata; отсутствующая,
  пустая, `unknown` или чужая версия закрывает открытие;
- recovery возвращает окончательно определённый профиль live GATT, а stop во
  время persistence retry быстро закрывает lease ровно один раз, сохраняя
  durable ingress для следующего поколения;
- recovery после подтверждённого индекса `0xffff` завершает поток явным
  `SENSOR_SEQUENCE_EXHAUSTED`, не пытаясь создать недопустимую следующую сессию;
- golden/replay поддерживает обе точные пары `GS1_V115/V115G` и
  `GS1_V120/V116A`; V115 trace сохраняет receive-time/add-time/future-clamp,
  проверяет максимум 17 samples и отклоняет V120 decoder до native processing;
- первый корректный пустой V115 envelope завершает transport handshake и
  запускает silence-watchdog, оставаясь пустым диагностическим результатом без
  медицинской записи.

JVM unit-наборы сенсора, алгоритма и core/data прошли; ARM instrumentation smoke
обоих режимов и обоих алгоритмических профилей компилируется. Его runtime-запуск
и сравнение с официальным приложением остаются физическим барьером первого
телефона и датчика.

Текущий результат программных проверок:

- `sensor:sibionics-datahandle`: 12 тестов;
- `sensor:sibionics-algorithm`: 36 JVM-тестов;
- `sensor:sibionics`: 280 тестов, включая golden/replay, recovery и fake-GATT
  lifecycle;
- `core:data`: 42 JVM-теста;
- lint обоих модулей — успешно;
- `git diff --check` — успешно.

Эти проверки не заменяют ARM-smoke, private capture и испытание отдельного
датчика. Они также не доказывают OEM-порядок callback: уже начатая GATT-операция
может пересечься со stop, хотя её поздний callback больше не принимается.

## TDD Android-приложения

Сначала тестами закреплены безопасные состояния интерфейса и тревог, затем
реализованы:

- крупное значение, единицы, тренд и время, затем график;
- график по реальному времени без линии через пропуск более 150 секунд;
- отдельные состояния «нет данных», «прогрев», «устарело» и «ошибка часов»;
- настройки нижнего/верхнего порога и времени потери сигнала;
- проверяемое хранение настроек с fail-closed возвратом к явным значениям;
- локальная политика low/high/rapid change/signal loss с hysteresis;
- повтор неподтверждённой активной тревоги каждые две минуты и подтверждение
  только текущего эпизода;
- запрос нужных Bluetooth, location и notification permissions по версии
  Android;
- проверка системной возможности показывать критическое уведомление;
- отдельный явно помеченный демо-сеанс, не сохраняемый как реальное измерение;
- fail-closed политика виджета для demo/stale/clock-mismatch/non-VALID;
- fail-closed чтение повреждённой confirmed-настройки датчика;
- одна начальная Room-схема `v1` со всеми таблицами сенсорного контура.

Recovery дополнительно проверяет точное семейство датчика до replay. TDD-тест
сначала воспроизвёл ошибочное принятие GS1Sb-записи для GS1-профиля, после чего
восстановление стало fail-closed до вызова алгоритма.

Room-тесты проверяют атомарность, точный повтор, конфликт, append-only ingress,
outcome и checkpoint. Файловый instrumentation-сценарий сохраняет checkpoint и
pending ingress, выполняет `close → reopen`, побайтово сверяет пакет, продолжает
commit и проверяет terminal outcome после второго reopen. Instrumented test APK
компилируется; runtime-прогон на устройстве или эмуляторе ещё требуется.

Для локального прогона по официальному SHA-256 установлены Android command-line
tools и Emulator `37.1.11`. Установка ARM system image остановлена без принятия
за владельца отдельной лицензии `android-sdk-arm-dbt-license`; сам тест не
выдаётся за выполненный.

Полный локальный Gradle-прогон unit-тестов, Android-test compilation/APK, lint,
debug APK и minified release-сборки прошёл. Debug APK выровнен, подписан только
локальным Android debug-ключом и проверен `apksigner`; это инженерная сборка, не
подписанный продуктовый релиз.

## TDD backend и Telegram

После тестов атомарности, конкурентности и перезапуска реализованы:

- Go API с обязательной `sequence`, проверкой диапазонов, времени, trend,
  quality и семейства сенсора;
- единый JSON-safe контракт `sequence` во всём тракте:
  `0..9 007 199 254 740 991` в Go, PostgreSQL, OpenAPI и web;
- запрет `simulator` в продуктовом endpoint;
- точный идемпотентный повтор и `409` при другом payload с тем же event ID либо
  при другом событии с той же парой `patientId + sensorId + sequence`;
- одна PostgreSQL-транзакция для measurement, alert changes и Telegram outbox;
- сериализация расчёта тревог по пациенту;
- долговечное начало мониторинга и восстановление signal-loss после restart;
- семейный snapshot и signal-loss только по последнему `VALID`; свежесть — по
  самому раннему из `sensorTime`, `phoneTime` и `receivedAt`;
- запрет `warming_up`/`degraded` освежать наблюдение или закрывать glucose- и
  signal-loss тревоги;
- согласованный семейный snapshot;
- подтверждение тревоги только для настроенного пациента;
- атомарная claim/lease выдача Telegram-сообщений с восстановлением после
  истечения аренды;
- независимые scheduler для signal-loss и delivery;
- bounded разбор ответа Telegram, обязательные `ok=true` и `message_id`, а также
  удаление bot token из ошибок;
- production fail-fast без PostgreSQL, при слабых token; любой режим fail-fast
  при отсутствующем/неизвестном `APP_ENV` или одинаковых device/family token.

Проверено локально:

- `go test ./...` — успешно;
- `go test -race ./...` — успешно;
- `go vet ./...` — успешно;
- `govulncheck ./...` на Go `1.26.5` — известных вызываемых уязвимостей нет;
- PostgreSQL 18 integration: атомарность, lease/retry, 32 параллельные записи и
  signal-loss restart — успешно.

Настоящий bot token и chat ID не подключены. Telegram Bot API не предоставляет
идемпотентный ключ отправки, поэтому при падении процесса строго после ответа
Telegram и до фиксации `sent` возможно повторное сообщение; потеря самой тревоги
при этом не допускается.

## Семейный сайт

Создан закрытый владелец-only демо-сайт:
`https://sladkaya-family-demo.rinatvfx.chatgpt.site`.

Он показывает крупное тестовое значение, график с настоящим разрывом, свежесть
и тестовую тревогу. На экране явно указано, что данные симулированные. Добавлена
чистая типизированная граница будущего API: trusted patient scope, `VALID`,
свежесть, часы и согласованность snapshot/history проверяются fail-closed;
смена sensor/family/sequence разрывает график. Всего проходят 23 теста, build,
lint, полный TypeScript-check и production audit. Метаданные и `og-v2.png`
прямо обозначают демо/симуляцию, не содержат имён людей и не обещают настоящую
доставку тревог. Сайт пока не вызывает backend API и не открыт семье без
настоящей авторизации.

## Git и безопасность

- продукт хранится в `https://github.com/adminhumor-art/helth.git`;
- токен GitHub не записан в remote URL, исходники, документацию или commit;
- локальные SDK, APK, кэши и любые private trace исключены через `.gitignore`;
- в продуктовом дереве нет названия исследованного стороннего приложения;
- исследовательские материалы остаются отдельными от продуктового репозитория.
- подготовлен least-privilege CI-шаблон с SHA-pinned actions для Go/PostgreSQL,
  web и Android; `actionlint` зелёный. Автоматический запуск пока не включён:
  GitHub token не имеет отдельного разрешения `Workflows`.

## TDD локальной доставки тревог

Сначала добавлены красные проверки долговечного signal-loss state, checksum,
stale generation/identity, out-of-order reading, clock rollback, exact/inexact
плана, reboot policy и delivery-pending. После них реализованы:

- отдельный системный watchdog последнего свежего `VALID`-измерения;
- неточный резерв рядом с exact wakeup и emergency fallback при ошибке
  планирования;
- единая атомарная граница alarm episode/watchdog для reading, acknowledge и
  системных receiver;
- pending episode и pre-arm повтора до durable save/notification, закрывающие
  окно смерти процесса;
- отметка `lastAlertAt` только после подтверждённого вызова уведомления;
- очистка signal-loss watchdog только после подтверждённого follow-up;
- fail-closed обработка повреждённого состояния без зависшего ongoing
  уведомления;
- разделение runtime-инварианта: отзыв локальной готовности не останавливает
  будущий продуктовый data/remote path, а демо закрывается отдельно.

После финального alternating-slot исправления `:app:testDebugUnitTest` проходит
169 из 169 тестов, `:app:lintDebug`, debug APK и Android-test APK собираются.
Android instrumentation исходники компилируются, но runtime-прогон ещё не
запускался, потому что устройство не подключено. Direct Boot до первого
разблокирования не реализован и явно оставлен P1-границей первого Samsung.

## Итоговый программный preflight без телефона и датчика

После закрытия V115 golden/replay-разрыва выполнен единый прогон с
`--rerun-tasks`: 647 Gradle-задач, 556 JVM-тестов, 0 failures/errors/skipped,
сборка app/core/algorithm/datahandle instrumentation APK, debug APK, lint и R8
minify — успешно.

APK дополнительно проверен как артефакт:

- package `com.sladkaya.app.debug`, подпись Android Debug и явная метка
  «Сладкая · тест»;
- только ARM ABI `arm64-v8a` и `armeabi-v7a`;
- 16-КБ zip alignment проходит; закреплённые native hashes проверяются каждой
  сборкой, а ARM64 `PT_LOAD` проверен с выравниванием `2**14`;
- `usesCleartextTraffic=false`, backup/device-transfer отключены;
- service и внутренние alarm receivers не экспортированы, системный boot
  receiver содержит только ожидаемые системные actions.

Lint: 0 ошибок и 26 предупреждений. Они относятся к KTX-стилю, inlined API с
явными SDK-gate, доступным обновлениям зависимостей, ARM-only ChromeOS и
side-load запросу исключения из оптимизации батареи; safety-критичных замечаний
не найдено. `govulncheck@v1.1.4` не нашёл вызываемых Go-уязвимостей,
`actionlint@v1.7.12` подтвердил CI-шаблон.

## Аудит продуктового пути после будущего физического допуска

Аудит зафиксировал, что безопасный диагностический тракт готов к первому
устройству, но автоматический переход в продукт намеренно ещё отсутствует:

- confirmed-config пока является только read-only marker и не загружает полную
  типизированную конфигурацию;
- `ConfiguredSensor` не открывает GATT, а coordinator всегда сохраняет
  `publishable=false`, `alarmEligible=false` и `measurement=null`;
- история, widget и uploader не подключены к реальному источнику;
- uploader работает только в процессе и содержит временный пустой BuildConfig
  seam вместо Android Keystore и долговечной WorkManager/outbox очереди;
- текущий backend привязывает один device token к одному patient из config;
  token→device→patient lookup для нескольких семей ещё не реализован.

Следующая TDD-итерация начинается с типизированного физического допуска в
окончательной Room `v1`, затем добавляет отдельный product runtime, атомарные
`measurement + outbox`, восстановление UI/alarm/widget, Android Keystore и
WorkManager. Обычный onboarding не сможет сам выдать физический допуск.

## Следующий физический барьер

Сенсорный тракт достиг физического барьера; независимая программная работа над
Android, backend, сайтом и Telegram продолжается. После появления отдельного
датчика и Samsung нужны:

1. ARM-smoke всех JNI-методов.
2. Private capture GS1 и GS1Sb и точное replay-сравнение.
3. Проверка resolver `код/DataMatrix → конкретный физический датчик`.
4. Полный цикл: активация, прогрев, фон, блокировка, обрыв, история, reboot,
   process kill, Doze и завершение срока датчика.
5. Проверка звука ночью: громкость, DND, канал, повтор и подтверждение.
6. Только после успешных ворот — отдельное решение владельца о допуске
   реальных значений к экрану, тревогам, backend и Telegram.

## TDD разрешённого ядра, общего GATT и семейного backend

2 августа 2026 года после повторного аудита продуктового пути закрыта следующая
программная итерация до появления телефона и датчика.

Сначала тестами воспроизведены, затем исправлены:

- сравнение `ByteArray` из двух независимых Room-query по ссылке, которое на
  устройстве отклоняло бы корректный checkpoint физического допуска;
- выдуманный общий прогрев: `V116A` теперь использует сохранённый start и
  строгую границу 45 минут, а `V115G` не получает синтетический warmup;
- потеря терминальности нативной ошибки после успешного checkpoint-commit;
  runtime немедленно закрывается, сохраняя уже подтверждённый префикс batch;
- отсутствие product-фасада над реальным GATT: один внутренний Bluetooth-engine
  теперь обслуживает раздельные diagnostic и approved product API, а product
  batch выходит только после durable cursor confirmation;
- риск потери следующего WorkManager wake-up во время активного drain;
  одноразовые работы используют последовательную `APPEND_OR_REPLACE`-цепочку;
- отсутствие явного шага разблокировки outbox после выдачи credential;
  добавлен проверенный порядок `persist → requeue → drain`. Production-адаптер
  массового requeue в Room пока не подставлен и не имитируется пустой операцией;
- запоздалый callback продуктовой сессии Android, который мог бы переписать
  состояние другого режима; product UI получил отдельный generation gate;
- `null`-перезапуск foreground service, который забывал подтверждённый product
  режим;
- backend readiness без получателя Telegram, неоднозначные сообщения нескольких
  пациентов, неограниченные scheduler-context и различие регистра UUID в URL.

Все реальные значения по-прежнему остаются за физическим выпускным барьером.
Новый product-фасад не подключён к `SensorForegroundService`, а текущую модель
допуска ещё требуется разделить на повторно используемый проверенный профиль и
активацию конкретного одноразового сенсора. Это зафиксировано как следующий P0,
а не скрыто за «готовым» маркером.

## Общий GATT engine и product facade

- TDD-тестами закреплено: cursor reject не выпускает значения; успешное durable
  подтверждение сохраняет полный batch и порядок; два batch не схлопываются;
  diagnostic output не превращается в product publication.
- Бывшая Android diagnostic-оболочка разделена на один внутренний Bluetooth
  engine и два фасада. Diagnostic API сохранён. Product facade использует
  approved opener вместе с durable publication repository и не имеет
  `latestDiagnostic`.
- Product batch проходит ограниченный `SUSPEND`-буфер. При заполнении producer
  ждёт collector вместо drop или неограниченного роста памяти. Runtime core
  events входят в GATT actor через backpressure, а не через callback `trySend`.
- Exact pending-ingress replay отдаёт восстановленный publication batch после
  проверки диапазона и до `markHandled`.
- Проверки: полный `:sensor:sibionics:testDebugUnitTest` и
  `:app:compileDebugKotlin` — успешно.

## Финальный preflight итерации

- Android: 680 Gradle-задач, 650 JVM-тестов без пропусков и ошибок, Room
  instrumentation APK, lint app/sensor, debug APK, Android-test APK и
  minified release/R8 — успешно.
- Backend: unit, race, vet, govulncheck, чистая PostgreSQL 18 integration-среда
  и `docker compose config` — успешно.
- Проверки репозитория не нашли секретов, случайных migration/legacy-механизмов
  или упоминаний исследовательского приложения в продуктовом коде.
- Реальные показания не разблокированы: первый телефон и отдельный датчик всё
  ещё нужны для ARM/JNI, BLE, прогрева, reboot/Doze и звукового испытания.

## TDD семейной HTTP-границы

- Красный тест показал, что family-session Bearer открывал snapshot, history и
  acknowledge, хотя OpenAPI документировал только cookie.
- Bearer fallback удалён. Все семейные маршруты принимают token только из
  `family_session`; неизвестная cookie не может откатиться к валидному Bearer.
- Device ingest не изменён и по-прежнему использует отдельный Bearer token.
- OpenAPI закрепляет cookie-only схему и обязательные для будущего issuer
  атрибуты `HttpOnly`, `Secure`, `SameSite=Strict`.
- Backend `go test ./...`, `go test -race ./...` и `go vet ./...` проходят.
- Rate limit и ограниченная пагинация/лимит history честно остаются P1 до
  публичного deploy.

## Честная граница Android provisioning

- Красная compile-проверка закрепила удаление вводящего в заблуждение типа
  `VerifiedRemoteProvisioningPayload`, который можно было создать из
  произвольных metadata и bytes.
- Контейнер переименован в внутренний `RemoteProvisioningPayload`; он по-прежнему
  одноразово владеет секретом, стирает исходный массив и не раскрывает данные в
  `toString`, но больше не заявляет о проверке identity.
- До пользовательского onboarding остаётся обязательным отдельный
  authenticated/signed parser provisioning-конверта.
- Целевой `RemoteSyncLifecycleTest` после изменения проходит.

## TDD окна commit → локальные эффекты

2 августа 2026 года повторный crash-window аудит обнаружил, что runtime сначала
эмитировал `Finalized`, а затем `Committed`, а buffered channel подтверждал лишь
RAM-enqueue. При смерти процесса это позволяло отметить BLE ingress обработанным
до фактического применения результата.

Сначала добавлены падающие тесты порядка, failure/cancellation, terminal prefix,
covered/partial recovery, missing rows, suffix output и закрытого generation.
После них реализовано:

- live `Committed → Finalized`; недоставленный commit не создаёт outcome;
- recovery-await не отправляет live events и возвращает результат единственному
  caller для exact validation;
- `sourceIngressId` проходит от journaled packet до raw sample, закреплён Room
  FK и проверяется до атомарной core-записи по полному ingress evidence;
- read-only Room reader восстанавливает publication только из канонически
  проверенных result/measurement/outbox/approval/binding, никогда из повторно
  декодированного пакета;
- `ALREADY_COVERED` и покрытый prefix `PARTIAL_OVERLAP` требуют exact-size
  contiguous Room proof; suffix не публикуется, partial остаётся pending;
- committed prefix терминальных `Rejected`, `Closed` и `StorageConflict`
  доставляется до terminal failure и не закрывает ingress;
- product batch требует typed `acknowledgeDurablyApplied`; reject имеет только
  ограниченный enum-код. RAM-enqueue не считается локальным эффектом.

Открытая fail-closed граница: новый duplicate ingress с тем же packet, но без
собственных linked raw rows, пока не дедуплицируется по одному checkpoint. Для
безопасного пропуска нужен exact earlier ingress с durable outcome и реальный
BLE duplicate/reconnect test. Также production local-effects cursor/outbox и
его consumer ещё не подключены, поэтому product facade нельзя считать готовой
ночной тревогой только из-за наличия ack API.

Целевые проверки итерации: `core:data` — 68 JVM-тестов; `sensor:sibionics` —
326 JVM-тестов; ошибок и пропусков нет. Android instrumentation sources для
`core:data` компилируются, `git diff --check` чист. Физический Android-прогон не
подменяется этой проверкой.

## Контрольный срез перед полным вертикальным циклом — 2 августа 2026

После замечания владельца порядок работ возвращён к продуктовому результату:
следующая итерация обязана связать уже проверенный сенсорный тракт с экраном,
локальной тревогой, виджетом, сервером и семейным сайтом. Дополнительные редкие
сценарии ядра не считаются основанием бесконечно откладывать эту связку.

Перед фиксацией среза выполнен единый Android preflight: 680 Gradle-задач,
723/723 JVM-теста без ошибок и пропусков, lint приложения и сенсорных модулей,
Room instrumentation APK, debug APK, Android-test APK и minified release/R8 —
успешно. Web: typecheck, lint, production build и 23/23 теста — успешно;
production audit не нашёл уязвимостей высокого уровня. Backend: 132/132 теста
на временной чистой PostgreSQL 18, тот же набор под race detector, vet,
govulncheck, проверка модулей, compose и CI-конфигурации — успешно.

На этом срезе честная граница остаётся такой: onboarding и диагностический BLE
тракт существуют, но foreground service ещё не запускает product GATT facade,
а экран, тревога, виджет и uploader ещё не получают подтверждённое измерение из
одного долговечного Room-конвейера. Это и есть следующий P0, а не новая серия
изолированных защитных доработок.
