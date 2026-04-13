# Нагрузочные тесты (k6)

Файл: [proxy.js](proxy.js)

Тестирует Proxy Service как единую точку входа. Охватывает все ключевые пути маршрутизации:

| Маршрут | Куда идёт | Что проверяет |
|---------|-----------|---------------|
| `GET /health` | Proxy (локально) | Доступность шлюза |
| `GET /api/movies` | Movies Service или Monolith (Strangler Fig) | Latency чтения каталога |
| `GET /api/users` | Monolith | Latency пользовательских запросов |
| `POST /api/events/movie` | Events Service → Kafka | Latency пути publish |

## Установка k6

```bash
# Windows (winget)
winget install k6 --source winget

# macOS
brew install k6

# Docker (без установки)
docker run --rm -i grafana/k6 run - < proxy.js
```

## Запуск

```bash
cd tests/load

# Smoke — 1 VU, 30 сек: убедиться, что система отвечает
k6 run proxy.js

# Load — плавный разгон до 10 VU, 3 мин: типичная нагрузка
k6 run -e SCENARIO=load proxy.js

# Stress — до 100 VU: поиск точки отказа
k6 run -e SCENARIO=stress proxy.js
```

### Переопределить адрес Proxy

```bash
k6 run -e BASE_URL=http://localhost:8000 proxy.js
# или для Kubernetes:
k6 run -e BASE_URL=http://cinemaabyss.example.com -e SCENARIO=load proxy.js
```

### Через Docker Compose (без установки k6)

```bash
docker run --rm -i \
  --network cinemaabyss-network \
  -e BASE_URL=http://proxy-service:8000 \
  -e SCENARIO=load \
  grafana/k6 run - < tests/load/proxy.js
```

## Пороговые значения (thresholds)

Тест считается пройденным если выполняются все условия:

| Метрика | Порог | Смысл |
|---------|-------|-------|
| `http_req_duration p(95)` | < 500 мс | 95% всех запросов быстрее 500 мс |
| `errors` | < 1% | Менее 1% запросов завершились ошибкой |
| `movies_duration p(95)` | < 400 мс | Каталог фильмов отвечает быстро |
| `events_duration p(95)` | < 800 мс | Публикация в Kafka допускает чуть больше |

## Сценарии

### smoke (дефолт)
1 VU, 30 секунд. Цель: убедиться, что сервис вообще отвечает и тест работает корректно. Запускать перед `load` / `stress`.

### load
Плавный разгон 0→10 VU за 30 с, плато 2 мин, спуск 30 с. Имитирует реальную рабочую нагрузку. Ожидаемый результат: все thresholds зелёные.

### stress
Разгон до 100 VU. Ищет предел: при каком количестве пользователей система начинает нарушать thresholds. При появлении ошибок — смотреть логи сервисов:

```bash
docker compose logs --tail=50 proxy-service
docker compose logs --tail=50 monolith
docker compose logs --tail=50 movies-service
```

## Итоговый вывод

По завершению k6 печатает сводку:

```
=== CinemaAbyss Load Test Summary (load) ===
✅ PASSED

Requests total  : 1842
Failed requests : 0
p95 latency     : 87 ms
p95 movies      : 72 ms
p95 events      : 210 ms
Error rate      : 0.00 %
```
