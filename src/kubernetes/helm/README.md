# CinemaAbyss — Helm Chart

Helm-чарт для деплоя приложения CinemaAbyss в кластер Kubernetes.

---

## Требования

- Kubernetes 1.16+
- Helm 3.0+
- Поддержка динамического provisioning PersistentVolume (если persistence включён)

---

## Быстрый старт (локально через Minikube)

### 1. Запустить кластер

```bash
minikube start
minikube addons enable ingress
```

### 2. Добавить домен в hosts

**Linux / Mac** — `/etc/hosts`
**Windows** — `C:\Windows\System32\drivers\etc\hosts`

```
127.0.0.1  cinemaabyss.example.com
```

### 3. (Опционально) Настроить pull secret для приватного реестра

Если образы лежат в приватном GHCR — заменить значение `imagePullSecrets.dockerconfigjson` в `values.yaml` на base64 от своего `~/.docker/config.json`:

```bash
cat ~/.docker/config.json | base64
```

---

## Установка

Из корня репозитория:

```bash
helm install cinemaabyss ./src/kubernetes/helm --namespace cinemaabyss --create-namespace
```

Дождаться готовности всех подов:

```bash
kubectl get pods -n cinemaabyss --watch
```

Запустить туннель Minikube (в отдельном терминале):

```bash
minikube tunnel
```

Проверить что API работает:

```bash
curl http://cinemaabyss.example.com/api/movies
```

Запустить API-тесты:

```bash
cd tests/postman && npm run test:kubernetes
```

---

## Обновление

Изменить процент миграции трафика на микросервисы (Strangler Fig) без переустановки:

```bash
helm upgrade cinemaabyss ./src/kubernetes/helm \
  --namespace cinemaabyss \
  --set config.moviesMigrationPercent=50
```

---

## Удаление

```bash
helm uninstall cinemaabyss -n cinemaabyss
kubectl delete namespace cinemaabyss
```

Если после переустановки Kafka выдаёт `InconsistentClusterIdException` — в PVC остались старые данные. Удалить вручную:

```bash
kubectl delete pvc --all -n cinemaabyss
```

---

## Параметры

### Глобальные

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `global.namespace` | Namespace для всех ресурсов | `cinemaabyss` |
| `global.domain` | Доменное имя приложения | `cinemaabyss.example.com` |

### PostgreSQL

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `database.host` | Хост PostgreSQL | `postgres` |
| `database.port` | Порт PostgreSQL | `5432` |
| `database.name` | Имя базы данных | `cinemaabyss` |
| `database.user` | Имя пользователя | `postgres` |
| `database.password` | Пароль (base64) | `cG9zdGdyZXNfcGFzc3dvcmQ=` |
| `database.image.repository` | Репозиторий образа | `postgres` |
| `database.image.tag` | Тег образа | `14` |
| `database.image.pullPolicy` | Политика загрузки образа | `IfNotPresent` |
| `database.resources.limits.cpu` | Лимит CPU | `1000m` |
| `database.resources.limits.memory` | Лимит памяти | `1Gi` |
| `database.resources.requests.cpu` | Запрос CPU | `500m` |
| `database.resources.requests.memory` | Запрос памяти | `512Mi` |
| `database.persistence.enabled` | Включить persistent storage | `true` |
| `database.persistence.size` | Размер PVC | `10Gi` |
| `database.persistence.accessMode` | Режим доступа PVC | `ReadWriteOnce` |

### Monolith

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `monolith.enabled` | Включить деплой monolith | `true` |
| `monolith.image.repository` | Репозиторий образа | `ghcr.io/db-exp/cinemaabysstest/monolith` |
| `monolith.image.tag` | Тег образа | `latest` |
| `monolith.image.pullPolicy` | Политика загрузки образа | `Always` |
| `monolith.replicas` | Количество реплик | `1` |
| `monolith.resources.limits.cpu` | Лимит CPU | `500m` |
| `monolith.resources.limits.memory` | Лимит памяти | `512Mi` |
| `monolith.resources.requests.cpu` | Запрос CPU | `100m` |
| `monolith.resources.requests.memory` | Запрос памяти | `128Mi` |
| `monolith.service.port` | Порт сервиса | `8080` |
| `monolith.service.targetPort` | Порт контейнера | `8080` |
| `monolith.service.type` | Тип сервиса | `ClusterIP` |

### Proxy Service

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `proxyService.enabled` | Включить деплой proxy | `true` |
| `proxyService.image.repository` | Репозиторий образа | `ghcr.io/db-exp/cinemaabysstest/proxy-service` |
| `proxyService.image.tag` | Тег образа | `latest` |
| `proxyService.image.pullPolicy` | Политика загрузки образа | `Always` |
| `proxyService.replicas` | Количество реплик | `1` |
| `proxyService.resources.limits.cpu` | Лимит CPU | `300m` |
| `proxyService.resources.limits.memory` | Лимит памяти | `256Mi` |
| `proxyService.resources.requests.cpu` | Запрос CPU | `100m` |
| `proxyService.resources.requests.memory` | Запрос памяти | `128Mi` |
| `proxyService.service.port` | Порт сервиса | `80` |
| `proxyService.service.targetPort` | Порт контейнера | `8000` |
| `proxyService.service.type` | Тип сервиса | `ClusterIP` |

### Movies Service

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `moviesService.enabled` | Включить деплой movies | `true` |
| `moviesService.image.repository` | Репозиторий образа | `ghcr.io/db-exp/cinemaabysstest/movies-service` |
| `moviesService.image.tag` | Тег образа | `latest` |
| `moviesService.image.pullPolicy` | Политика загрузки образа | `Always` |
| `moviesService.replicas` | Количество реплик | `1` |
| `moviesService.resources.limits.cpu` | Лимит CPU | `300m` |
| `moviesService.resources.limits.memory` | Лимит памяти | `256Mi` |
| `moviesService.resources.requests.cpu` | Запрос CPU | `100m` |
| `moviesService.resources.requests.memory` | Запрос памяти | `128Mi` |
| `moviesService.service.port` | Порт сервиса | `8081` |
| `moviesService.service.targetPort` | Порт контейнера | `8081` |
| `moviesService.service.type` | Тип сервиса | `ClusterIP` |

### Events Service

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `eventsService.enabled` | Включить деплой events | `true` |
| `eventsService.image.repository` | Репозиторий образа | `ghcr.io/db-exp/cinemaabysstest/events-service` |
| `eventsService.image.tag` | Тег образа | `latest` |
| `eventsService.image.pullPolicy` | Политика загрузки образа | `Always` |
| `eventsService.replicas` | Количество реплик | `1` |
| `eventsService.resources.limits.cpu` | Лимит CPU | `300m` |
| `eventsService.resources.limits.memory` | Лимит памяти | `256Mi` |
| `eventsService.resources.requests.cpu` | Запрос CPU | `100m` |
| `eventsService.resources.requests.memory` | Запрос памяти | `128Mi` |
| `eventsService.service.port` | Порт сервиса | `8082` |
| `eventsService.service.targetPort` | Порт контейнера | `8082` |
| `eventsService.service.type` | Тип сервиса | `ClusterIP` |

### Kafka

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `kafka.enabled` | Включить деплой Kafka | `true` |
| `kafka.image.repository` | Репозиторий образа | `wurstmeister/kafka` |
| `kafka.image.tag` | Тег образа | `2.13-2.7.0` |
| `kafka.image.pullPolicy` | Политика загрузки образа | `IfNotPresent` |
| `kafka.replicas` | Количество реплик | `1` |
| `kafka.resources.limits.cpu` | Лимит CPU | `1000m` |
| `kafka.resources.limits.memory` | Лимит памяти | `1Gi` |
| `kafka.resources.requests.cpu` | Запрос CPU | `200m` |
| `kafka.resources.requests.memory` | Запрос памяти | `512Mi` |
| `kafka.persistence.enabled` | Включить persistent storage | `true` |
| `kafka.persistence.size` | Размер PVC | `5Gi` |
| `kafka.persistence.accessMode` | Режим доступа PVC | `ReadWriteOnce` |
| `kafka.topics` | Конфигурация топиков | см. values.yaml |

### Zookeeper

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `zookeeper.enabled` | Включить деплой Zookeeper | `true` |
| `zookeeper.image.repository` | Репозиторий образа | `wurstmeister/zookeeper` |
| `zookeeper.image.tag` | Тег образа | `latest` |
| `zookeeper.image.pullPolicy` | Политика загрузки образа | `IfNotPresent` |
| `zookeeper.replicas` | Количество реплик | `1` |
| `zookeeper.resources.limits.cpu` | Лимит CPU | `500m` |
| `zookeeper.resources.limits.memory` | Лимит памяти | `512Mi` |
| `zookeeper.resources.requests.cpu` | Запрос CPU | `100m` |
| `zookeeper.resources.requests.memory` | Запрос памяти | `256Mi` |
| `zookeeper.persistence.enabled` | Включить persistent storage | `true` |
| `zookeeper.persistence.size` | Размер PVC | `1Gi` |
| `zookeeper.persistence.accessMode` | Режим доступа PVC | `ReadWriteOnce` |

### Ingress

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `ingress.enabled` | Включить ingress | `true` |
| `ingress.className` | Класс ingress | `nginx` |
| `ingress.annotations` | Аннотации ingress | см. values.yaml |
| `ingress.hosts` | Конфигурация хостов | см. values.yaml |

### Конфигурация приложения

| Параметр | Описание | Значение по умолчанию |
|---|---|---|
| `config.gradualMigration` | Включить постепенную миграцию (Strangler Fig) | `true` |
| `config.moviesMigrationPercent` | Процент трафика movies, идущего в микросервис | `100` |

---

## Архитектура

Приложение реализует паттерн **Strangler Fig** — монолит постепенно вытесняется микросервисами через единую точку входа (Proxy).

### Маршрутизация запросов

```
Клиент
  │
  ▼
Ingress (nginx)
  │
  ▼
Proxy Service :8000
  │
  ├── /api/movies/**  ──── gradualMigration=true ────►  50%* → Movies Service :8081
  │                                                      50%* → Monolith :8080
  │
  ├── /api/events/**  ──────────────────────────────►  Events Service :8082
  │                                                          │
  │                                                          ▼
  │                                              Kafka (publish: movie-events,
  │                                                     user-events, payment-events)
  │                                                          │
  │                                                          ▼
  │                                              EventConsumer (тот же сервис,
  │                                                     consumer group events-service-consumer)
  │
  ├── /api/users/**   ──────────────────────────────►  Monolith :8080
  ├── /api/payments/**  ────────────────────────────►  Monolith :8080
  └── /api/subscriptions/**  ──────────────────────►  Monolith :8080
```

`*` — процент настраивается через `config.moviesMigrationPercent` (сейчас `50`). При `100` весь трафик `/api/movies` идёт в Movies Service, монолит для этих запросов больше не задействован.

### Таблица маршрутов

| Путь | Назначение | Примечание |
|---|---|---|
| `/health` | Proxy (локально) | Собственный health check прокси |
| `/api/movies/**` | Movies Service / Monolith | Вероятностный split по `migrationPercent` |
| `/api/events/movie` | Events Service | POST → Kafka topic `movie-events` |
| `/api/events/user` | Events Service | POST → Kafka topic `user-events` |
| `/api/events/payment` | Events Service | POST → Kafka topic `payment-events` |
| `/api/users/**` | Monolith | Только монолит, миграция не планируется |
| `/api/payments/**` | Monolith | Только монолит, миграция не планируется |
| `/api/subscriptions/**` | Monolith | Только монолит, миграция не планируется |

### Компоненты

| Сервис | Язык | Назначение |
|---|---|---|
| **Monolith** | Go | Пользователи, подписки, платежи; movies до завершения миграции |
| **Proxy Service** | Java | Единая точка входа, реализует Strangler Fig маршрутизацию |
| **Movies Service** | Go | Микросервис фильмов — принимает трафик по мере роста `migrationPercent` |
| **Events Service** | Java | Принимает HTTP-запросы на `/api/events/**`, публикует события в Kafka и сам же их консьюмит (logging, будущая обработка) |
| **PostgreSQL** | — | Общая база данных всех сервисов |
| **Kafka** | — | Async message broker; топики: `movie-events`, `user-events`, `payment-events` |
| **Zookeeper** | — | Координация Kafka-брокера |

---

## Persistent Storage

PersistentVolume создаётся для PostgreSQL, Kafka и Zookeeper через динамический provisioning.

Отключить persistence (например, для ephemeral-окружений):

```bash
helm install cinemaabyss ./src/kubernetes/helm \
  --set database.persistence.enabled=false \
  --set kafka.persistence.enabled=false \
  --set zookeeper.persistence.enabled=false
```

---

## Image Pull Secret

Чарт создаёт Secret для загрузки образов из приватного реестра. Значение берётся из `imagePullSecrets.dockerconfigjson` в `values.yaml`.
