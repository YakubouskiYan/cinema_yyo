# Архитектура микросервисов CinemaAbyss

## Обзор

В проекте реализована следующая функциональность:

- Постепенный вывод микросервисов с использованием паттерна Strangler Fig
- Развёртывание в Kubernetes с Helm-чартами и GitOps через ArgoCD
- API Gateway (Proxy Service) как единая точка входа
- Event-driven архитектура через Kafka
- Production-grade CI/CD pipeline на GitHub Actions

---

## Компоненты

### Монолит

Исходное приложение на Go. Обрабатывает домены, которые ещё не вынесены в микросервисы:

- Управление пользователями (`/api/users`)
- Платежи (`/api/payments`)
- Подписки (`/api/subscriptions`)
- Фильмы (`/api/movies`) — до завершения миграции

Расположен в `src/monolith/`.

### Микросервисы

#### Movies Service

Выделен из монолита, обрабатывает всю функциональность, связанную с фильмами:

- Метаданные фильмов
- Рейтинги
- Жанры

Расположен в `src/microservices/movies/`.

#### Events Service

Принимает HTTP-запросы на `/api/events/**` и публикует события в Kafka.

- `POST /api/events/movie` → топик `movie-events`
- `POST /api/events/user` → топик `user-events`
- `POST /api/events/payment` → топик `payment-events`

HTTP-контракт (что принимает от клиента) и Kafka-контракт (что публикуется) разделены на уровне модели: `*Request` — входящий DTO, `*Event` — сообщение в топик с дополнительными полями `event_id` и `published_at`, генерируемыми сервисом.

Расположен в `src/microservices/events/`.

#### Proxy Service (API Gateway)

Реализует паттерн Strangler Fig — постепенный переход от монолита к микросервисам:

| Путь | Назначение |
|---|---|
| `/api/movies/**` | `migrationPercent`% → Movies Service, остаток → Monolith |
| `/api/events/**` | Events Service |
| `/api/users/**` | Monolith |
| `/api/payments/**` | Monolith |
| `/api/subscriptions/**` | Monolith |

Расположен в `src/microservices/proxy/`.

---

## Паттерн Strangler Fig

Прокси маршрутизирует трафик через вероятностный split:

```
MOVIES_MIGRATION_PERCENT=0    → 100 % /api/movies → Monolith
MOVIES_MIGRATION_PERCENT=50   →  50 % → Movies Service, 50 % → Monolith
MOVIES_MIGRATION_PERCENT=100  → 100 % → Movies Service
```

При `GRADUAL_MIGRATION=false` — весь трафик идёт в монолит независимо от `MOVIES_MIGRATION_PERCENT`.

---

## Инфраструктура

### Kubernetes

Манифесты Kubernetes расположены в `src/kubernetes/`. Основной способ деплоя — Helm.

### Helm Charts

Параметризованный чарт для установки одной командой. Расположен в `src/kubernetes/helm/`.
Подробности: [src/kubernetes/helm/README.md](src/kubernetes/helm/README.md)

### Порядок развёртывания

Полное руководство: [src/kubernetes/DEPLOY.md](src/kubernetes/DEPLOY.md)

### CI/CD Pipeline

Подробное описание всех workflow: [.github/workflows/WORKFLOWS.md](.github/workflows/WORKFLOWS.md)

Главный пайплайн (`docker-build-push.yml`) запускается на push в `main` и Pull Request:

```
[Тесты + Coverage] ──┐
                      ├──→ Сборка образов → Trivy scan → GHCR → GitOps PR → ArgoCD → Kubernetes
[CodeQL SAST]      ──┘          ↓                 ↓
                           GitHub Security   SBOM (CycloneDX)
```

Ключевые характеристики:
- **CodeQL** запускается параллельно с тестами и блокирует сборку при нахождении уязвимостей
- **Trivy** сканирует собранный образ до push в GHCR — образ с CRITICAL/HIGH CVE не публикуется
- **GitOps** — обновление `values.yaml` происходит через Pull Request, не прямым push в main
- **Concurrency control** — параллельные пуши не создают race condition

---

## Запуск локально через Docker Compose

1. Убедиться что установлены Docker и Docker Compose

2. Запустить сервисы:
   ```bash
   docker compose up -d
   ```

После запуска сервисы доступны:

| Сервис | URL |
|---|---|
| API Gateway (Proxy) | http://localhost:8000 |
| Monolith | http://localhost:8080 |
| Movies Service | http://localhost:8081 |
| Events Service | http://localhost:18082 |
| Kafka UI | http://localhost:18090 |

3. Остановить сервисы:
   ```bash
   docker compose down -v
   ```

4. После изменений пересобрать и перезапустить:
   ```bash
   docker compose build && docker compose up -d
   ```

---

## Тестирование API

Проект включает набор Postman-тестов, запускаемых через Newman.

Покрытие:
- **Monolith** — пользователи, фильмы, платежи, подписки
- **Movies Service** — health check, операции с фильмами
- **Events Service** — health check, публикация событий (movie / user / payment)
- **Proxy Service** — health check, маршрутизация запросов

### Запуск тестов

```bash
cd tests/postman
npm install

# Локально (прямые порты сервисов)
npm run test:local

# В Docker Compose (через сеть контейнеров)
npm run test:docker

# В Kubernetes
npm run test:kubernetes
```

Подробности: [tests/postman/README.md](tests/postman/README.md)

### Ручная проверка Strangler Fig

```bash
# Текущее поведение (50% → Movies Service)
curl http://localhost:8000/api/movies

# Изменить процент миграции в docker-compose.yml:
# MOVIES_MIGRATION_PERCENT: "100"
# и перезапустить прокси:
docker compose up -d proxy-service
```

Проверить события в Kafka — Kafka UI: http://localhost:8090
