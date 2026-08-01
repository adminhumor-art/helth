# Golden/replay-контур GS1/GS1Sb

Обновлено: 1 августа 2026 года.

## Назначение и граница

Контур воспроизводит синтетический либо закрытый эталонный поток GS1/GS1Sb без
Bluetooth-устройства и сравнивает:

- точные encrypted bytes каждого BLE notification;
- ingress attempt, ordinal и время приёма;
- результат двойного декодирования в raw samples;
- исходный диагностический вывод ядра, trend и три warning-кода;
- SHA-256 бинарного состояния после каждого минутного шага;
- закрытие и повторное открытие нативного контекста между попытками подключения.

Replay не создаёт `GlucoseReading`, не пишет в продуктовое хранилище и сам по
себе не открывает выпускной барьер. GS3 в этот формат не проводится.

## Классификация и приватность

Синтетический fixture имеет классификацию `SYNTHETIC_PUBLIC_FIXTURE`. Одних
enum и префиксов недостаточно: publication gate принимает только канонические
bytes единственного проверенного fixture с закреплённым SHA-256. Каталог
resources также закрыт `.gitignore`-allowlist. Его trace id, algorithm version и
binary set обязаны иметь префикс `synthetic-`, а identity evidence — тип
`SYNTHETIC`.

Любая запись физического сенсора имеет классификацию
`PRIVATE_SENSITIVE_EVIDENCE`. Точные encrypted packets обратимо декодируются
транспортным кодом, а raw-значения и абсолютное время остаются чувствительными
данными. Поэтому реальный capture не называется обезличенным, не хранится в Git
и передаётся только через отдельное закрытое хранилище. Пути `golden/private/` и
файлы `*.private.gs1.trace` исключены в `.gitignore`. Принудительное изменение
allowlist или закреплённого SHA является отдельным рискованным review-действием,
а не автоматическим признанием нового файла безопасным.

Из полей private trace исключены canonical MAC, `sensorId`, точный код упаковки,
DataMatrix и ключ HMAC. Вместо них сохраняются:

- `HMAC_SHA256_TRACE_LOCAL_V1` и 64-символьный lower-case MAC pseudonym;
- вид identity evidence: ручной код + advertisement, DataMatrix + advertisement
  либо `SYNTHETIC`;
- HMAC-SHA-256 versioned private identity evidence;
- отдельный HMAC-SHA-256 точного case-sensitive 8-символьного sensitivity input.

Секрет HMAC локален для набора trace и хранится вне репозитория. Replay получает
только key-bearing capability, которая вычисляет HMAC канонического versioned
message. Код сенсора и ключ не записываются в trace и не появляются в ошибках.

## Формат `v1`

Файл — canonical UTF-8. Header имеет фиксированный порядок:

```text
GS1-GOLDEN-TRACE
version=0001
payload-length=0000000000
payload-sha256=<64 lower-case hex>

<payload ровно объявленной длины>
```

`payload-length` — число bytes, а `payload-sha256` — SHA-256 точного payload.
После payload не допускается ни одного byte. Payload оканчивается одним `LF`;
порядок строк и полей фиксирован. Строковые значения, которые не являются
закреплёнными enum/числами/хешами, кодируются lower-case hex от UTF-8 bytes.

Метаданные закрепляют trace id, provenance/privacy classification, семейство,
профиль, algorithm version, binary set, `GS1_V120`, обязательный `STANDARD`,
источник `PACKAGE_CODE`, `NORMAL`, точные IEEE-754 Float bits коэффициента,
HMAC точного sensitivity input и pseudonymous identity evidence.

Каждая строка `notification` содержит attempt/pseudonym, ordinal, ingress time,
полный encrypted packet и SHA-256, ожидаемый исход `GS1_DATA`, `NON_DATA` либо
`REJECTED`, признак дешифрования и число samples. За каждым sample идут:

- `sample`: index, sensor time, raw current, raw temperature, reindex;
- `diagnostic`: точные IEEE-754 bits native и diagnostic output, trend, три
  warning-кода, state SHA-256 и при наличии checkpointed error code.

## Fail-closed правила

Parser и planner отвергают файл до decoder/native при неизвестной версии,
неверном magic/encoding/размере/поле, повреждении SHA-256, обрыве, хвосте,
неизвестной строке, конфликте attempt/ordinal/index/time, non-finite output,
неверном state hash либо sensitivity metadata.

Первый sample имеет index `1`; следующие index и sensor time идут без разрыва с
шагом 60 секунд. Один notification содержит не более 29 samples, packet — не
более 250 bytes, payload — не более 16 MiB.

Header ограничен 256 bytes. Payload разбирается последовательным курсором без
списка всех строк; одна строка ограничена 2048 символами до `split`. Поэтому
16 MiB коротких строк не превращаются в миллионы объектов.

## Runner

Planner повторно проверяет даже программно созданный объект. Runner передаёт
неизменённый packet существующему `Gs1PacketVerifier`, сравнивает весь decoded
batch до первого нативного шага и затем открывает существующий stateful session
через injected factory.

До первого нативного шага runner требует HMAC-capability и сверяет `STANDARD`,
token source, sensitivity encoding и точные Float bits коэффициента. Exact
8-символьный input проверяется HMAC без записи кода или ключа.

После каждого sample runner побитово сверяет output, trend, warnings,
профиль/версию/binary set, token source/init mode, checkpoint token binding и
state hash. Лишь после совпадения вызывается `confirmPersisted`.

При смене attempt нативный контекст закрывается и открывается заново с последним
checkpoint. Это `context reopen`, а не проверка смерти процесса или повторного
открытия Room. Отдельный instrumentation-тест
`checkpoint + pending ingress → Room close → reopen → next commit → terminal
outcome → second reopen` реализован и компилируется, но ещё не запускался на
Android. Process-kill и продолжение полного sensor replay остаются обязательными
отдельными барьерами.

## Текущий fixture

`android/sensor/sibionics/src/test/resources/golden/gs1-synthetic-v1.trace`
имеет `SYNTHETIC_TEST_ONLY` и `SYNTHETIC_PUBLIC_FIXTURE`. Его packets, raw samples
и diagnostic bits созданы только для codec/planner/runner. Fake native вычисляет
outputs независимо от expected trace; отдельные mutation-тесты меняют каждое
поле, state, restore, init и sensitivity binding.

Fixture не получен с датчика, не является значениями для лечения или сравнением
с закреплённым эталоном. До реального golden остаются private capture/export вне
Git, прогон закреплённого эталона и ARM replay обоих профилей.
