# Postman / Newman тесты

Полная документация по запуску тестов — в [../README.md](../README.md).

## Структура каталога

| Файл | Назначение |
|------|------------|
| `CinemaAbyss.postman_collection.json` | Основная коллекция тестов (по сервисам) |
| `CinemaAbyss_E2E.postman_collection.json` | E2E-сценарий сквозного потока через Proxy |
| `local.environment.json` | Переменные для запуска с хоста (`127.0.0.1:порт`) |
| `docker.environment.json` | Переменные для запуска внутри Docker-сети (имена контейнеров) |
| `kubernetes.environment.json` | Переменные для запуска внутри Kubernetes |
| `run-tests.js` | CLI-скрипт запуска через Newman |
| `Dockerfile` | Образ для запуска тестов внутри Docker-сети |
| `reports/` | HTML и JUnit XML отчёты после прогона |

## Где запускать каждое окружение

| Скрипт | Где запускать | Как резолвятся хосты |
|--------|---------------|----------------------|
| `test:local` | Хост | `127.0.0.1:порт` — пробрасываемые порты Docker |
| `test:docker` | Внутри Docker-сети | `monolith:8080` — Docker DNS |
| `test:kubernetes` | Pod внутри кластера | Kubernetes Service DNS |

> **Важно:** `test:docker` использует имена контейнеров (`monolith`, `movies-service` и т.д.),
> которые разрешаются только внутри Docker-сети. С хоста используйте `test:local`.

## Запуск

### С хоста (сервисы запущены через Docker Compose)

```bash
npm install
npm run test:local
```

### Внутри Docker-сети

```bash
# Собрать образ (один раз)
docker build -t cinemaabyss-api-tests .

# Запустить тесты в сети docker compose
docker run --rm --network cinemaabyss-network cinemaabyss-api-tests \
  node run-tests.js --environment docker
```

### E2E сценарий

```bash
# С хоста
npm run test:e2e

# Внутри Docker-сети
npm run test:e2e:docker

# Внутри Kubernetes
npm run test:e2e:kubernetes
```

### Отдельная группа тестов

```bash
npm run test:monolith
npm run test:movies
npm run test:events
npm run test:proxy
```

## Отчёты

После каждого прогона в `reports/` создаются:
- `report-<env>-<timestamp>.html` — HTML-отчёт
- `junit-report-<env>-<timestamp>.xml` — JUnit XML для CI
