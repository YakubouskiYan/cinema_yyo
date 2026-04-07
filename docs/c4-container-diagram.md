# CinemaAbyss — To-Be Architecture (C4 Container Diagram)

## Целевая архитектура (Strangler Fig Migration)

Ниже представлена контейнерная диаграмма в нотации C4, описывающая целевое состояние
платформы CinemaAbyss после полного разделения монолита на домены.

Исходный файл диаграммы: [c4-container-diagram.mmd](c4-container-diagram.mmd)

---

## Описание доменов

| Домен | Сервис | Порт | Ответственность |
|-------|--------|------|-----------------|
| **Gateway** | API Gateway (Proxy) | 8000 | Единая точка входа, Strangler Fig routing |
| **Legacy** | Monolith | 8080 | Users + Payments + Subscriptions (временно) |
| **Movies** | Movies Service | 8081 | Каталог фильмов, жанры, рейтинги |
| **Users** *(to extract)* | Users Service | 8083 | Учётные записи пользователей |
| **Billing** *(to extract)* | Payments Service | 8084 | Платежи + интеграция с платёжным шлюзом |
| **Billing** *(to extract)* | Subscriptions Service | 8085 | Подписки пользователей |
| **Events** | Events Service | 8082 | Доменные события через Kafka |

---

## Взаимодействие компонентов

### Синхронное (HTTP/REST)

```
Клиент
  └─→ API Gateway :8000
        ├─→ Monolith :8080          ← /api/users, /api/payments, /api/subscriptions
        ├─→ Movies Service :8081    ← /api/movies  (0–100 % via MOVIES_MIGRATION_PERCENT)
        └─→ Events Service :8082    ← /api/events/*
```

### Асинхронное (Kafka)

```
Movies Service ──HTTP──→ Events Service
Monolith       ──HTTP──→ Events Service  ──produce──→ Kafka topics ──consume──→ Events Service (лог)
Payments Svc   ──HTTP──→ Events Service

Топики:  movie-events | user-events | payment-events
```

### Паттерн Strangler Fig (миграция трафика)

```
MOVIES_MIGRATION_PERCENT=0    →  100 % запросов /api/movies → Monolith
MOVIES_MIGRATION_PERCENT=50   →   50 % → Movies Service,  50 % → Monolith
MOVIES_MIGRATION_PERCENT=100  →  100 % → Movies Service  (монолит отключён для movies)
```

---

## Принципы целевой архитектуры

1. **Database per Service** — каждый домен владеет своей схемой БД; общий PostgreSQL — переходное состояние.
2. **Strangler Fig** — постепенное вытеснение монолита через API Gateway без остановки системы.
3. **Event-Driven** — доменные события публикуются в Kafka; сервисы реагируют асинхронно.
4. **Single Entry Point** — все клиентские запросы идут через API Gateway; прямые вызовы к сервисам запрещены.
5. **Circuit Breaker (Istio)** — сетевая надёжность реализуется на уровне service mesh (см. Задание 5).
