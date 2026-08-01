# Происхождение нативных бинарников

Обновлено: 1 августа 2026 года.

## Правило

Расчётный код и данные берутся из официальных APK SiBionics. Для
`armeabi-v7a` файлы упаковываются без изменений. Для `arm64-v8a` используется
проверенное механическое выравнивание ELF под 16-КБ страницы Android:

- машинный код и содержимое всех секций не меняются;
- GNU Build ID и `DT_NEEDED` сохраняются;
- перед вторым `PT_LOAD` добавляется только нулевое заполнение;
- файловые offsets и `p_align` приводятся к 16 КБ;
- результат и полный список файлов закрепляются SHA-256 в каждом Android-модуле.

Инструмент преобразования, его восемь тестов и полный аудит хранятся в
отдельном исследовательском каталоге. Продукт не использует найденные во внешних
сборках JNI-файлы с изменённой системной зависимостью.

## ARM64: исходный и упакованный SHA-256

| Файл | Официальный исходник | Упакованный 16 КБ |
|---|---|---|
| `libnative-algorithm-jni-v115G.so` | `42f5affeaa098993b0544410152cd9a44f9de8feca94a1d8386e9683d3179a50` | `c08e1c2626aff583a835c91c0f70e7e479c495098767461181560d049627ae45` |
| `libnative-algorithm-jni-v116A.so` | `1ceab2ffa14528ba84c01b831f9e04f202a84dd7ee94d9e15e678917685e4b5e` | `33f25693386d10bc0f63fbebc18cc721bd9bca47e612d664af2723d52d9f3b5d` |
| `libnative-algorithm-v1_1_5G.so` | `0d5dd4b2618d9edd92b5693a321f861d71cfdc656486816836e4d3438f9893aa` | `3df46f71611b0cb590ca97bdb3424ae9e20a57cad058afa632aec4e1d54cf488` |
| `libnative-algorithm-v1_1_6A.so` | `e27eabaca52b8ef6d376f10dee76eac59735d2a783c48834f1003724d09ee794` | `3eb160078da015190eaf7e70c775d197082c70c55644b78deb1da2f82d75016b` |
| `libnative-encrypy-decrypt-v110.so` | `7deca2302bee4aff557c261de89565cf43744f7e6430e8d6bbd3c6823b7a92d4` | `3a35e64dbf78f0bc960d8ed5d984a06be8eecdd187db1b0ca37fd20df4cd0d6b` |
| `libnative-sensitivity-v110.so` | `9b176c18d4346c04cc1e55fec29c5e892b72ec136f5a8bbf4bfe1c43a84109fe` | `e2801b4d0c5e67f4efb1b93cf56c05b9eae9dc30dfa07bab86a2f07405843b98` |
| `libnative-struct2json.so` | `9365cff687dd744977dcd646c4187ee4b158af33699bdd0e0e3be83002f4572e` | `170c23fcc1d2df2b809bf7f685dc0207ca98fb0aea81c3942db1d8ab3f6efccb` |
| `libdata-handle-lib.so` | `761f0aab72b35010839e90620c348ee71b888b25682252360a7af16f150bd1d7` | `03fcdd49471a4904a95f2e3e747b7b40e9eb7aa8e4468f32c5ea4b08af810443` |

Независимый runtime-тест `dlopen/JNI/replay` на реальном 16-КБ Android остаётся
обязательным выпускным барьером.
