# CI

`github-actions.yml` — проверенный шаблон GitHub Actions для Android, backend и
web. Он намеренно хранится вне `.github/workflows`, поэтому сейчас не запускается
автоматически.

Для активации файл нужно перенести в `.github/workflows/ci.yml` и отправить
токеном с разрешением GitHub `Workflows: read and write`. Текущий repo-scoped
токен имеет право записи кода, но GitHub отклоняет изменение workflow-файлов.

Перед переносом шаблон проверяется командой:

```sh
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 ci/github-actions.yml
```
