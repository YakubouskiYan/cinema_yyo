# Тестирование CinemaAbyss

## Виды тестов

| Тип | Где живёт | Что проверяет | Требования |
|-----|-----------|---------------|------------|
| **Интеграционные (Go)** | `src/monolith/`, `src/microservices/movies/` | CRUD-операции против реальной БД | Docker Compose запущен |
| **Веб-слой (Java)** | `src/microservices/events/src/test/`, `src/microservices/proxy/src/test/` | HTTP-контракты контроллеров | Ничего (Kafka/upstream замокированы) |
| **Kafka (Java)** | `src/microservices/events/src/test/` | Сообщения реально попадают в Kafka | Ничего (`@EmbeddedKafka`) |
| **E2E API (Postman)** | `tests/postman/` | Сквозные HTTP-сценарии через все сервисы | Docker Compose или K8s запущен |
| **Нагрузочные (k6)** | `tests/load/` | Latency и error rate под нагрузкой | Docker Compose запущен |

---

## Быстрый старт

### 1. Запустить инфраструктуру

```bash
# Из корня проекта cinema_yyo
docker compose up -d
```

> **Важно:** всегда запускайте `docker compose` из директории `cinema_yyo` — там находится `docker-compose.yml`.

Дождаться, пока все сервисы поднимутся:

```bash
docker compose ps   # все должны быть в статусе "running" или "healthy"
```

Если меняли исходный код — пересобирайте образы явно:

```bash
docker compose up -d --build
```

### 2. Запустить все тесты

```bash
# Go-тесты (монолит + movies-service, нужна БД)
cd src/monolith             && go test -v ./...
cd src/microservices/movies && go test -v ./...

# Java-тесты (events + proxy, без внешних зависимостей)
# Включает как веб-слой (@WebMvcTest), так и Kafka (@EmbeddedKafka)
cd src/microservices/events && mvn test
cd src/microservices/proxy  && mvn test

# E2E API-тесты Postman (сквозной сценарий через Proxy)
cd tests/postman && npm install && npm run test:e2e:docker

# Нагрузочный тест (smoke, убедиться что всё ок)
k6 run tests/load/proxy.js
```

---

## Интеграционные тесты (Go)

Файлы: [src/monolith/main_test.go](../src/monolith/main_test.go) и [src/microservices/movies/main_test.go](../src/microservices/movies/main_test.go)

Тесты используют реальную PostgreSQL из Docker Compose. Если БД недоступна — тесты пропускаются автоматически (`os.Exit(0)`), а не падают.

### Монолит — покрытие

| Тест | Что проверяет |
|------|---------------|
| `TestHealth` | GET /health → 200 `{status: true}` |
| `TestUsers_GetAll_ReturnsList` | GET /api/users → список из seed-данных |
| `TestUsers_CreateAndGetByID` | POST создаёт пользователя, GET по id возвращает его |
| `TestUsers_MethodNotAllowed` | DELETE → 405 |
| `TestMovies_GetAll_ReturnsList` | GET /api/movies → список из seed-данных |
| `TestMovies_CreateAndGetByID_WithGenres` | POST с жанрами, GET по id — жанры сохранены |
| `TestMovies_Create_TransactionRollbackOnEmptyTitle` | Пустой title — ответ без паники |
| `TestPayments_CreateAndGetByUserID` | POST платёж, GET по user_id |
| `TestPayments_GetByID` | GET по id конкретного платежа |
| `TestSubscriptions_CreateAndGetByUserID` | POST подписки, GET по user_id |
| `TestSubscriptions_GetByID` | GET по id конкретной подписки |

### Movies Service — покрытие

| Тест | Что проверяет |
|------|---------------|
| `TestHealth` | GET /api/movies/health → 200 |
| `TestMovies_GetAll_ReturnsList` | GET /api/movies → не пустой список |
| `TestMovies_CreateAndGetByID_WithGenres` | POST с жанрами, GET — жанры round-trip |
| `TestMovies_MethodNotAllowed` | DELETE → 405 |
| `TestMovies_CreateWithoutGenres` | POST без жанров — Genres пустой массив при GET |

### Переопределить адрес БД

```bash
TEST_DB_URL="postgres://user:pass@host:5432/db?sslmode=disable" go test ./...
```

---

## Тесты веб-слоя (Java)

Не требуют запущенных сервисов — внешние зависимости замокированы.

### Events Service

Файл: [src/microservices/events/src/test/.../EventsControllerTest.java](../src/microservices/events/src/test/java/com/cinemaabyss/events/EventsControllerTest.java)

`@WebMvcTest` + `@MockBean EventProducer` — Kafka не нужна.

| Тест | Что проверяет |
|------|---------------|
| `health_returns200WithStatusTrue` | GET /api/events/health → 200 |
| `movieEvent_returns201_withEventFields` | POST /movie → 201, поля `event_id` и `published_at` сгенерированы |
| `movieEvent_publishesToCorrectTopic` | Продюсер вызван с топиком `movie-events` |
| `userEvent_returns201_withEventFields` | POST /user → 201, поля события корректны |
| `userEvent_publishesToCorrectTopic` | Продюсер вызван с топиком `user-events` |
| `paymentEvent_returns201_withEventFields` | POST /payment → 201, поля события корректны |
| `paymentEvent_publishesToCorrectTopic` | Продюсер вызван с топиком `payment-events` |

```bash
cd src/microservices/events
mvn test
```

### Proxy Service

Файлы: [src/microservices/proxy/src/test/.../ProxyControllerTest.java](../src/microservices/proxy/src/test/java/com/cinemaabyss/proxy/ProxyControllerTest.java) и `ProxyControllerMigrationTest.java`

`@SpringBootTest` + `MockRestServiceServer` — реальные upstream-сервисы не нужны.

| Тест | Что проверяет |
|------|---------------|
| `health_returns200WithStatusTrue` | GET /health обрабатывается локально |
| `usersRequest_routesToMonolith` | /api/users → запрос уходит в monolith |
| `paymentsRequest_routesToMonolith` | /api/payments → monolith |
| `subscriptionsRequest_routesToMonolith` | /api/subscriptions → monolith |
| `moviesRequest_withMigrationDisabled_routesToMonolith` | GRADUAL_MIGRATION=false → /api/movies → monolith |
| `eventsRequest_routesToEventsService` | /api/events/* → events-service |
| `upstreamError_propagatedToClient` | upstream 500 → прокси возвращает 5xx клиенту |
| `moviesRequest_withFullMigration_alwaysRoutesToMoviesService` (×5) | MIGRATION_PERCENT=100 → всегда movies-service |

```bash
cd src/microservices/proxy
mvn test
```

---

## API-тесты Postman (Newman)

Каталог: `tests/postman/`

Тесты гоняются через Newman — CLI-runner для Postman-коллекций. Проверяют сквозные HTTP-сценарии через все сервисы.

### Покрытие

| Папка в коллекции | Сервис | Сценарии |
|-------------------|--------|----------|
| Monolith Service | Монолит :8080 | Пользователи, фильмы, платежи, подписки |
| Movies Microservice | Movies :8081 | Health check, CRUD фильмов |
| Events Microservice | Events :18082 | Health check, публикация событий (movie / user / payment) |
| Proxy Service | Proxy :8000 | Health check, маршрутизация запросов |

### Установка

```bash
cd tests/postman
npm install
```

> Node.js недоступен локально? Используйте Docker (см. ниже).

### Запуск

#### Против локально запущенных сервисов (прямые порты)

```bash
npm run test:local
```

Адреса: монолит `8080`, movies `8081`, events `18082` (порт 8082 зарезервирован Windows), proxy `8000`.

#### Против Docker Compose (через сеть контейнеров)

```bash
npm run test:docker
```

> **Важно:** `test:docker` использует имена контейнеров (`proxy-service`, `monolith` и т.д.) как хосты — эти имена разрешаются только внутри Docker-сети. С хост-машины используйте `test:local`.

#### Против Kubernetes

```bash
npm run test:kubernetes
```

#### Запуск через Docker (если Node.js не установлен)

```bash
# Собрать образ (один раз)
docker build -t cinemaabyss-api-tests tests/postman/

# Запустить тесты в сети docker compose
docker run --rm \
  --network cinemaabyss-network \
  cinemaabyss-api-tests \
  node run-tests.js --environment docker
```

### Запуск отдельной группы тестов

Используйте npm-скрипты — они автоматически передают нужные переменные:

```bash
npm run test:monolith
npm run test:movies
npm run test:events
npm run test:proxy
```

> **Важно:** при запуске отдельной папки переменные (`userId`, `movieId`, `paymentId`) не устанавливаются предыдущими шагами коллекции. `npm run test:events` автоматически передаёт дефолтные значения через `--env-var`. Если вы запускаете напрямую через `node run-tests.js --folder "..."` — передайте переменные явно:
> ```bash
> node run-tests.js --folder "Events Microservice" --env-var movieId=1 --env-var userId=1 --env-var paymentId=1
> ```

### Параметры CLI

```
--environment, -e   Окружение: local | docker | kubernetes  (default: local)
--folder,      -f   Папка коллекции для запуска
--reporters,   -r   Репортеры через запятую (default: cli,htmlextra,junit)
--bail,        -b   Остановиться на первой ошибке
--timeout,     -t   Таймаут запроса в мс (default: 10000)
--env-var          Переопределить переменную окружения (key=value), можно повторять
```

### Отчёты

После запуска в `tests/postman/reports/` создаются:

- `report-<env>-<timestamp>.html` — HTML-отчёт
- `junit-report-<env>-<timestamp>.xml` — JUnit XML для CI

### Переменные окружения

| Файл | Для чего |
|------|----------|
| `local.environment.json` | Локальный запуск (`localhost:порт`) |
| `docker.environment.json` | Docker Compose (имена контейнеров) |
| `kubernetes.environment.json` | Kubernetes (NodePort / Ingress) |

---

---

## Kafka-тесты (EmbeddedKafka)

Файл: [src/microservices/events/src/test/.../EventsKafkaIntegrationTest.java](../src/microservices/events/src/test/java/com/cinemaabyss/events/EventsKafkaIntegrationTest.java)

`@SpringBootTest` + `@EmbeddedKafka` — поднимает in-process Kafka брокер. Тест:
1. Отправляет HTTP POST в контроллер через MockMvc
2. Читает сообщение из Kafka-топика через `KafkaTestUtils`
3. Проверяет payload и ключ сообщения

| Тест | Что проверяет |
|------|---------------|
| `movieEvent_messageArrivesInKafka_withGeneratedFields` | Payload содержит данные запроса + `event_id` + `published_at` |
| `movieEvent_kafkaKey_containsMovieId` | Ключ сообщения = `"movie-{id}"` |
| `userEvent_messageArrivesInKafka_withGeneratedFields` | Аналогично для user-events |
| `userEvent_kafkaKey_containsUserId` | Ключ = `"user-{id}"` |
| `paymentEvent_messageArrivesInKafka_withGeneratedFields` | Аналогично для payment-events |
| `paymentEvent_kafkaKey_containsPaymentId` | Ключ = `"payment-{id}"` |

```bash
cd src/microservices/events
mvn test   # запускает и @WebMvcTest, и @EmbeddedKafka тесты вместе
```

---

## E2E-сценарий (Postman)

Файл: [tests/postman/CinemaAbyss_E2E.postman_collection.json](postman/CinemaAbyss_E2E.postman_collection.json)

Единый связный поток из 8 шагов — все запросы идут через Proxy Service:

```
01. POST /api/users            → создать пользователя      → сохранить userId
02. POST /api/subscriptions    → оформить подписку premium  → сохранить subscriptionId
03. POST /api/movies           → добавить фильм             → сохранить movieId
04. POST /api/events/movie     → событие просмотра          → проверить event_id
05. POST /api/events/payment   → событие оплаты             → проверить сумму
06. GET  /api/users?id=...     → пользователь существует
07. GET  /api/subscriptions?user_id=... → подписка premium активна
08. GET  /api/movies?id=...    → фильм с жанрами сохранён
```

```bash
cd tests/postman
npm install

# С хост-машины (сервисы запущены через Docker Compose)
npm run test:e2e

# Внутри Docker-сети (например, из Newman-контейнера)
npm run test:e2e:docker
```

> `test:e2e` использует `127.0.0.1` и работает с хост-машины.  
> `test:e2e:docker` использует имена контейнеров и требует запуска внутри сети `cinemaabyss-network`.

---

## Нагрузочные тесты (k6)

Файл: [tests/load/proxy.js](load/proxy.js) — полная документация: [tests/load/README.md](load/README.md)

```bash
# Smoke (1 VU, 30 с) — быстрая проверка что всё поднято
k6 run tests/load/proxy.js

# Load (10 VU, ~3 мин) — типичная нагрузка
k6 run -e SCENARIO=load tests/load/proxy.js

# Stress (до 100 VU) — поиск предела
k6 run -e SCENARIO=stress tests/load/proxy.js

# Через Docker (если k6 не установлен)
docker run --rm -i --network cinemaabyss-network \
  -e BASE_URL=http://proxy-service:8000 \
  -e SCENARIO=load \
  grafana/k6 run - < tests/load/proxy.js
```

Пороги считаются пройденными при:
- p95 latency < 500 мс (все эндпоинты)
- Error rate < 1%
