# CinemaAbyss — GitOps & DevSecOps: полный сценарий

Здесь описано что происходит от момента когда разработчик пушит код до момента когда новая версия оказывается в кластере Kubernetes.

---

## Три workflow-файла

| Файл | Когда запускается | Назначение |
|---|---|---|
| [docker-build-push.yml](docker-build-push.yml) | Push в `main`, Pull Request в `main`, Release, вручную | Главный пайплайн: тесты + CodeQL → сборка → сканирование → деплой |
| [api-tests.yml](api-tests.yml) | Pull Request в `main`, вручную | Только API-тесты, без сборки образов |
| [codeql.yml](codeql.yml) | Каждый понедельник в 03:00 UTC | Еженедельный SAST-анализ — ловит новые CVE в уже задеплоенном коде |

---

## Управление параллельными запусками (Concurrency)

Пайплайн защищён от гонок при параллельных пушах на двух уровнях:

**Глобальный уровень** — отменяет устаревший запуск пайплайна целиком:

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

Если разработчик делает два пуша подряд — первый запуск отменяется, выполняется только последний.

**GitOps уровень** — сериализует обновления манифестов без отмены:

```yaml
concurrency:
  group: gitops-update
  cancel-in-progress: false
```

Обновления values.yaml никогда не прерываются, чтобы не оставить манифест в частично обновлённом состоянии.

---

## Поведение на Pull Request

При открытии PR в `main` пайплайн работает в ограниченном режиме:

| Job | PR | Push в main |
|---|---|---|
| Тесты (`test`) | ✅ выполняется | ✅ выполняется |
| CodeQL SAST (`codeql`) | ✅ выполняется | ✅ выполняется |
| Сборка + сканирование (`build-scan-push`) | ✅ только build + Trivy | ✅ build + Trivy + push |
| Обновление манифестов (`update-manifests`) | ❌ пропускается | ✅ выполняется |
| API-тесты (`api-tests`) | ❌ пропускается | ✅ выполняется |

На PR образы не публикуются в GHCR — только проверяется что они собираются и не содержат критических уязвимостей.

---

## Полная схема сценария

```
Разработчик делает git push в ветку main
              │
              ▼
┌──────────────────────────────────────────────┐
│            docker-build-push.yml             │
│                                              │
│  Job 1: Тесты кода       Job 2: CodeQL SAST  │
│  (параллельно)           (параллельно)        │
│  ┌────────┐ ┌────────┐   ┌────────────────┐  │
│  │monolith│ │movies  │   │ java-kotlin    │  │
│  └────────┘ └────────┘   └────────────────┘  │
│  ┌────────┐ ┌────────┐   ┌────────────────┐  │
│  │ proxy  │ │events  │   │ go             │  │
│  └────────┘ └────────┘   └────────────────┘  │
│  coverage / junit / jacoco  SARIF → Security  │
│              │                │               │
│              └───────┬────────┘               │
│                      ▼ (оба прошли)           │
│  Job 3: Сборка + Сканирование + Push          │
│  (параллельно для каждого сервиса)            │
│                                              │
│    docker build → Trivy scan → GHCR         │
│         ↓              ↓                    │
│      образ         GitHub Security          │
│      в GHCR           (SARIF)               │
│                    SBOM (CycloneDX)         │
│              │                               │
│              ▼ (всё прошло)                  │
│  Job 4: Обновление манифестов (GitOps)       │
│                                              │
│    values.yaml: image.tag = sha-abc123      │
│    git commit → Pull Request → merge        │
│              │                               │
│              ▼                               │
│  Job 5: Интеграционные тесты                │
│                                              │
│    docker compose up → readiness check       │
│    Newman → отчёт                           │
└──────────────────────────────────────────────┘
              │
              ▼
        ArgoCD замечает изменение
        в values.yaml (новый SHA-тег)
              │
              ▼
        helm upgrade в кластере
        Kubernetes деплоит новые поды

──────────────────────────────────────────
Отдельно, каждый понедельник:

┌──────────────────────────────────────────┐
│           codeql.yml (schedule)          │
│  CodeQL по расписанию — ловит новые CVE  │
│  в уже задеплоенном коде                │
└──────────────────────────────────────────┘
```

---

## Job 1 и Job 2 — Тесты и CodeQL (параллельно)

**Файл:** [docker-build-push.yml](docker-build-push.yml) → jobs `test` и `codeql`

Запускаются одновременно сразу после checkout — оба нуждаются только в исходном коде. Сборка образов (`build-scan-push`) заблокирована до тех пор, пока **оба** job не завершатся успешно.

**Почему CodeQL стоит на этом этапе:**

Смысл — поймать уязвимость до того, как образ собран. Если CodeQL находит SQL-injection или command injection в Java-сервисе — нет смысла тратить время на `docker build`, Trivy и push. Образ с уязвимостью просто не появится.

Принцип "чем раньше — тем дешевле":
- Уязвимость в коде → исправить одну строку
- Уязвимость в образе в GHCR → нужно пересобрать, переопубликовать, обновить манифесты
- Уязвимость в кластере → инцидент, откат, постмортем

---

## Job 1 — Тесты кода

**Файл:** [docker-build-push.yml](docker-build-push.yml) → job `test`

Запускается параллельно с CodeQL. Пока тесты не прошли — ни один образ не собирается.

Все 4 сервиса тестируются **параллельно** — не нужно ждать пока проверят монолит чтобы начать проверять movies-service.

### Go-сервисы (monolith, movies-service)

```bash
go test ./... -v -race -count=1 -coverprofile=coverage.out
```

- `-race` — ищет гонки данных (race condition). Это когда два потока одновременно читают и пишут одну переменную — приводит к непредсказуемым багам
- `-count=1` — отключает кеш тестов, каждый раз прогоняет заново
- `-v` — подробный вывод
- `-coverprofile=coverage.out` — записывает покрытие кода тестами

Файл `coverage.out` сохраняется как **GitHub Artifact** (`go-coverage-<service>`).

### Java-сервисы (proxy-service, events-service)

```bash
mvn -B verify -DskipITs=true --no-transfer-progress
```

- `verify` — запускает: компиляция → unit-тесты (Surefire) → JaCoCo → упаковка в jar
- `-DskipITs=true` — пропускает интеграционные тесты (Failsafe), выполняет только unit-тесты
- `-B` — batch mode, без интерактивных подсказок (нужно для CI)

Сохраняются как **GitHub Artifacts**:
- `junit-results-<service>` — JUnit XML из `target/surefire-reports/`
- `jacoco-coverage-<service>` — HTML-отчёт из `target/site/jacoco/`

### Настройка `fail-fast: false`

Если proxy-service упал — тесты events-service продолжаются. Это позволяет увидеть все проблемы сразу, а не по одной.

---

## Job 2 — CodeQL SAST

**Файл:** [docker-build-push.yml](docker-build-push.yml) → job `codeql`

Запускается параллельно с тестами. Анализирует исходный код на уязвимости до сборки любого образа.

```
Checkout кода
      │
      ├── java-kotlin: setup-java → mvn verify -DskipTests
      │                (компилирует .class файлы для трейсинга)
      │
      └── go: autobuild
              (go build всех модулей)
      │
      ▼
CodeQL analyze
  queries: security-extended + security-and-quality
  ищет: SQL injection, command injection, path traversal,
        небезопасная десериализация, crypto issues, OWASP Top 10
      │
      ▼
SARIF → GitHub Security → Code scanning alerts
```

Если CodeQL находит CRITICAL/HIGH — `build-scan-push` не стартует. Код не компилируется в образ до устранения уязвимости.

**Отличие от `codeql.yml`:**

`codeql.yml` теперь содержит **только** еженедельный запуск по расписанию. Он не дублирует этот job — он ловит новые CVE в базе данных CodeQL для кода, который уже задеплоен в production, когда никаких пушей не происходит.

---

## Job 3 — Сборка, сканирование и публикация образов

**Файл:** [docker-build-push.yml](docker-build-push.yml) → job `build-scan-push`

Запускается после Job 1 (тесты) и Job 2 (CodeQL) — оба должны пройти. Для каждого из 4 сервисов — четыре шага по порядку:

### Шаг 1 — Сборка образа (локально, без push)

```
docker build → локальный Docker daemon
```

Образ собирается на раннере GitHub, но **не отправляется в реестр**. Тег `<service>:scan` — временный, только для сканирования.

Используется кеш GitHub Actions (`type=gha,mode=max`) — если Dockerfile и зависимости не изменились, слои берутся из кеша. Повторная сборка на шаге push занимает секунды, так как все слои уже в кеше.

На Pull Request — шаг выполняется, но вход в GHCR пропускается (`Log in to GHCR` пропускается при `github.event_name == 'pull_request'`).

### Шаг 2 — Trivy: сканирование уязвимостей

Используется **фиксированная версия** `aquasecurity/trivy-action@0.20.0` (ранее `@master`) — для воспроизводимости пайплайна.

```
Trivy проверяет локальный образ
       │
       ├── найдены CRITICAL/HIGH CVE → пайплайн падает, push не происходит
       │
       └── всё чисто → переходим к push
```

**Что проверяет Trivy простыми словами:**

Trivy смотрит на все установленные пакеты внутри образа — библиотеки Go, Java jar-файлы, системные пакеты Alpine/Debian — и сравнивает с базой известных уязвимостей (CVE).

- **CRITICAL** — критическая дыра, например удалённое выполнение кода без авторизации
- **HIGH** — серьёзная уязвимость, например утечка данных или повышение привилегий
- **MEDIUM / LOW** — пропускаются (`severity: CRITICAL,HIGH`), чтобы не блокировать пайплайн из-за малозначимых проблем
- **Unfixed** — если для уязвимости ещё нет патча ни в одной версии пакета, она игнорируется (`ignore-unfixed: true`). Нет смысла блокировать деплой из-за того, что автор библиотеки ещё не выпустил исправление

**Результат сканирования — SARIF-файл:**

SARIF — стандартный формат отчёта о безопасности. Trivy записывает туда все найденные проблемы.

```
trivy-monolith.sarif
trivy-movies-service.sarif
trivy-proxy-service.sarif
trivy-events-service.sarif
```

Эти файлы загружаются в GitHub Security tab шагом `Upload SARIF` — даже если пайплайн упал (`if: always()`). Так история уязвимостей не теряется.

В итоге в репозитории появляется раздел **Security → Code scanning alerts** где видно:

- какой сервис уязвим
- какой конкретно пакет
- насколько критично
- есть ли уже патч

### Шаг 3 — Push в GHCR

Только если Trivy не нашёл CRITICAL/HIGH **и** это не Pull Request. Образ отправляется в GitHub Container Registry (`ghcr.io`).

**Теги образа:**

| Тег | Пример | Когда |
|---|---|---|
| `sha-<7 символов>` | `sha-abc1234` | всегда — точная привязка к коммиту |
| `<ветка>` | `main` | всегда |
| `<версия>` | `v1.2.3` | при release |
| `latest` | `latest` | только для ветки main |

**Аутентификация в GHCR:**

Используется `GITHUB_TOKEN` — временный токен, который GitHub создаёт автоматически для каждого запуска пайплайна. Он живёт только пока выполняется workflow и даёт доступ только к этому репозиторию. Твой личный токен нигде не используется.

Права прописаны явно в job:
```yaml
permissions:
  packages: write        # push образов в GHCR
  security-events: write # загрузка SARIF в GitHub Security
```

### Шаг 4 — SBOM (Software Bill of Materials)

После push генерируется SBOM в формате **CycloneDX**:

```
sbom-monolith.cdx.json
sbom-movies-service.cdx.json
sbom-proxy-service.cdx.json
sbom-events-service.cdx.json
```

SBOM — это полный список всех зависимостей внутри образа: библиотеки, версии, лицензии. Используется для аудита supply chain и соответствия требованиям безопасности (SLSA, SSDF). Файлы сохраняются как GitHub Artifacts.

---

## Job 4 — GitOps: обновление манифестов

**Файл:** [docker-build-push.yml](docker-build-push.yml) → job `update-manifests`

Запускается только при push в `main` (не при release, PR и не при ручном запуске).

**Что происходит:**

```
Берётся SHA текущего коммита (первые 7 символов)
              │
              ▼
values.yaml обновляется через yq v4.43.1 (pinned):
  monolith.image.tag      = "sha-abc1234"
  moviesService.image.tag  = "sha-abc1234"
  proxyService.image.tag   = "sha-abc1234"
  eventsService.image.tag  = "sha-abc1234"
              │
              ▼
Открывается Pull Request в ветку main
  ветка: gitops/update-sha-abc1234
  title: "chore(gitops): update image tags to sha-abc1234"
  после merge → ArgoCD синхронизирует кластер
```

Вместо прямого `git push` в main теперь создаётся **Pull Request** через `peter-evans/create-pull-request@v6`. Это даёт:

- **Видимость** — каждый деплой виден в списке PR, с описанием что именно меняется
- **Ревью** — при необходимости деплой можно заблокировать до ревью
- **Идемпотентность** — если изменений нет, PR не создаётся

Если несколько пушей попадают в `update-manifests` одновременно — они выполняются последовательно (`cancel-in-progress: false`), чтобы не оставить values.yaml в промежуточном состоянии.

**После merge PR:**

ArgoCD постоянно следит за веткой `main`. Как только видит изменение в `values.yaml` — автоматически запускает `helm upgrade` в кластере. Kubernetes заменяет старые поды на новые с образом `sha-abc1234`.

Это и есть **GitOps**: git является единственным источником истины о том, что задеплоено. Никто не делает `kubectl apply` вручную — всё через коммит в репозиторий.

---

## Job 5 — Интеграционные API-тесты

**Файл:** [docker-build-push.yml](docker-build-push.yml) → job `api-tests`

Запускается после Job 2 (образы уже в GHCR). Пропускается на Pull Request. Проверяет что все сервисы работают вместе — не по отдельности, а в связке.

```
docker compose up -d      ← поднимает все сервисы
      │
      ▼
retry-loop: ожидание готовности 3 сервисов (без фиксированного sleep)
  proxy    → http://localhost:8000/health  (до 30 попыток × 10 с)
  movies   → http://localhost:8001/health  (до 30 попыток × 10 с)
  events   → http://localhost:8002/health  (до 30 попыток × 10 с)
      │
      ▼
Newman запускается в Docker-контейнере
в той же сети что и сервисы
      │
      ├── тесты прошли → отчёт загружается как Artifact
      │
      └── тесты упали → отчёт всё равно загружается (if: always())
      │
      ▼
docker compose down       ← гарантированная очистка
```

**Readiness check без `sleep`:**

Вместо `sleep 90` используются retry-loop'ы — каждый сервис проверяется через `curl /health` с паузой 10 секунд между попытками. Если сервис не поднялся за 5 минут — выводятся логи compose и пайплайн падает с понятной ошибкой.

Тесты написаны в Postman-коллекции [`tests/postman/CinemaAbyss.postman_collection.json`](../../tests/postman/CinemaAbyss.postman_collection.json) и прогоняются через Newman (CLI-runner для Postman).

Отчёты сохраняются как **GitHub Artifacts** — их можно скачать из интерфейса Actions даже если тесты упали.

---

## Второй workflow: api-tests.yml

**Файл:** [api-tests.yml](api-tests.yml)

Запускается при открытии **Pull Request** в `main` или вручную. Делает только Job 4 из основного пайплайна — поднимает сервисы и прогоняет Newman.

**Зачем нужен отдельно:**

При PR образы в GHCR уже есть (они собраны из предыдущих push в main). Нет смысла пересобирать и сканировать — просто проверяем что код из PR не сломал API.

---

## Третий workflow: codeql.yml

**Файл:** [codeql.yml](codeql.yml)

Запускается **только по расписанию**. Push и PR триггеры убраны — CodeQL на push/PR теперь выполняется внутри `docker-build-push.yml` как Job 2.

### Когда запускается

| Триггер | Условие |
|---|---|
| Расписание | Каждый понедельник в 03:00 UTC |

**Зачем нужен отдельный файл если CodeQL уже есть в пайплайне:**

CodeQL в `docker-build-push.yml` проверяет **новый код** в момент пуша. Но база сигнатур CodeQL обновляется постоянно — новые паттерны уязвимостей добавляются каждую неделю. Код, который был чист в январе, может получить alert в марте — просто потому что GitHub добавил новую сигнатуру CVE.

Еженедельный запуск ловит эти ретроспективные находки в коде, который уже лежит в main и задеплоен в production, без необходимости делать новый пуш.

### Что анализирует

Два языка в параллельных матрицах:

| Матрица | Анализирует | Сервисы |
|---|---|---|
| `java-kotlin` | Java 21, Maven | proxy-service, events-service |
| `go` | Go modules | monolith, movies-service |

### Как работает

```
Checkout кода
      │
      ├── java-kotlin: setup-java + mvn verify -DskipTests (только компиляция)
      │                CodeQL трейсит .class файлы
      │
      └── go: autobuild (go build всех модулей)
              CodeQL трейсит бинарники
      │
      ▼
CodeQL analyze
  queries: security-extended + security-and-quality
      │
      ▼
SARIF → GitHub Security → Code scanning alerts
```

**Зачем нужна компиляция для Java:**

CodeQL анализирует не текст, а семантику кода. Для Java ему нужны скомпилированные `.class` файлы — только так он видит полные цепочки вызовов и потоки данных между классами.

**Что ищет `security-extended`:**

- SQL/Command injection
- Path traversal
- Десериализация ненадёжных данных
- Небезопасная криптография
- OWASP Top 10

Результаты попадают в **Security → Code scanning alerts**. CodeQL **не блокирует деплой** — это отдельный канал обратной связи по безопасности. Если нужно сделать CodeQL обязательной проверкой перед merge, это настраивается через **Branch Protection Rules** (Settings → Branches → Required status checks).

---

## Artifacts: что сохраняется после каждого запуска

| Artifact | Содержимое | Job |
|---|---|---|
| `go-coverage-<service>` | `coverage.out` — покрытие Go-кода | `test` |
| `junit-results-<service>` | JUnit XML из Surefire | `test` |
| `jacoco-coverage-<service>` | HTML-отчёт JaCoCo | `test` |
| `api-test-reports` | Отчёты Newman/Postman | `api-tests` |
| `sbom-<service>` | CycloneDX SBOM образа | `build-scan-push` |

Все artifacts доступны во вкладке **Actions → <run> → Artifacts** в интерфейсе GitHub.

---

## Итоговый DevSecOps-сценарий одной строкой

```
Код → [Тесты+Coverage ‖ CodeQL] → Сборка → [Trivy+SBOM] → GHCR → GitOps PR → ArgoCD → Kubernetes
              ↓                        ↓
       GitHub Security          GitHub Security
       (SAST — источник)        (SCA — образ)
```

Два независимых слоя сканирования безопасности:
- **CodeQL** — анализирует исходный код (SAST): логика, потоки данных, инъекции
- **Trivy** — анализирует собранный образ (SCA): уязвимые пакеты, CVE в зависимостях

**Безопасность встроена в каждый шаг:**

| Шаг | Защита |
|---|---|
| CodeQL на исходном коде (Job 2) | Блокирует сборку при SQL injection, command injection, OWASP Top 10 — раньше всего |
| Тесты Go с `-race` | Ловит гонки данных до деплоя |
| Trivy до push в GHCR | Образ с критической CVE в зависимостях никогда не попадёт в реестр |
| Trivy pinned `@0.20.0` | Воспроизводимое поведение сканера, нет неожиданных изменений |
| CodeQL еженедельно (`codeql.yml`) | Ловит новые CVE в уже задеплоенном коде без новых пушей |
| SARIF в GitHub Security | История уязвимостей (SAST + SCA) видна всей команде |
| SBOM (CycloneDX) | Полная прозрачность зависимостей для аудита supply chain |
| `GITHUB_TOKEN` вместо личного токена | Компрометация не даёт доступа за пределы репозитория |
| Pull secret не в git | Учётные данные GHCR не хранятся в репозитории |
| GitOps через PR, не direct push | Каждый деплой проходит через ревью и виден в истории PR |
| Concurrency control | Нет гонок при параллельных пушах, нет конфликтов в values.yaml |
| ArgoCD | Никакого ручного `kubectl apply` — только через git |
| mTLS в Istio | Весь трафик между сервисами зашифрован внутри кластера |
| TLS на Ingress | Внешний трафик только по HTTPS |
