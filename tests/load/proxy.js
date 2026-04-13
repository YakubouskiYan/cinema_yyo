/**
 * CinemaAbyss — нагрузочный тест для Proxy Service (k6)
 *
 * Три сценария запускаются независимо, управляются через переменную окружения SCENARIO:
 *   k6 run proxy.js                          → smoke (дефолт)
 *   k6 run -e SCENARIO=load proxy.js         → load
 *   k6 run -e SCENARIO=stress proxy.js       → stress
 *
 * Переменные среды:
 *   BASE_URL   — адрес Proxy Service (default: http://localhost:8000)
 *   SCENARIO   — smoke | load | stress       (default: smoke)
 */

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ── Кастомные метрики ─────────────────────────────────────────────────────────

const errorRate   = new Rate('errors');
const moviesTrend = new Trend('movies_duration', true);
const eventsTrend = new Trend('events_duration', true);

// ── Сценарии ──────────────────────────────────────────────────────────────────

const SCENARIOS = {
  smoke: {
    // Проверяем, что система вообще отвечает без нагрузки
    executor: 'constant-vus',
    vus: 1,
    duration: '30s',
  },
  load: {
    // Типичная нагрузка: плавный разгон → плато → спуск
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '30s', target: 10 },   // разгон
      { duration: '2m',  target: 10 },   // плато
      { duration: '30s', target: 0  },   // спуск
    ],
  },
  stress: {
    // Стресс-тест: ищем точку отказа
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '30s', target: 20  },
      { duration: '1m',  target: 50  },
      { duration: '30s', target: 100 },
      { duration: '1m',  target: 100 },
      { duration: '30s', target: 0   },
    ],
  },
};

const scenario = __ENV.SCENARIO || 'smoke';

export const options = {
  scenarios: {
    [scenario]: SCENARIOS[scenario],
  },

  // ── Пороговые значения ─────────────────────────────────────────────────────
  thresholds: {
    // 95-й перцентиль времени ответа — не дольше 500 мс
    http_req_duration: ['p(95)<500'],
    // Не более 1% ошибочных запросов
    errors: ['rate<0.01'],
    // Для эндпоинта /api/movies отдельный порог
    movies_duration: ['p(95)<400'],
    // Для /api/events — публикация в Kafka может быть чуть медленнее
    events_duration: ['p(95)<800'],
  },
};

// ── Конфигурация ──────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8000';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// ── Вспомогательные функции ───────────────────────────────────────────────────

function isOk(res) {
  return res.status >= 200 && res.status < 300;
}

function record(metric, res) {
  metric.add(res.timings.duration);
}

// ── Основной сценарий (выполняется каждым VU в цикле) ────────────────────────

export default function () {

  // 1. Health check — самый быстрый, проверяем каждую итерацию
  group('health', () => {
    const res = http.get(`${BASE_URL}/health`);
    const ok = check(res, {
      'health: статус 200': (r) => r.status === 200,
      'health: {status:true}': (r) => {
        try { return JSON.parse(r.body).status === true; } catch { return false; }
      },
    });
    errorRate.add(!ok);
  });

  sleep(0.2);

  // 2. Получить список фильмов — читающая операция, высокая частота
  group('GET /api/movies', () => {
    const res = http.get(`${BASE_URL}/api/movies`);
    record(moviesTrend, res);
    const ok = check(res, {
      'movies: статус 200':      (r) => r.status === 200,
      'movies: ответ — массив':  (r) => {
        try { return Array.isArray(JSON.parse(r.body)); } catch { return false; }
      },
    });
    errorRate.add(!ok);
  });

  sleep(0.3);

  // 3. Получить список пользователей — запросы идут через proxy → monolith
  group('GET /api/users', () => {
    const res = http.get(`${BASE_URL}/api/users`);
    const ok = check(res, {
      'users: статус 200':     (r) => r.status === 200,
      'users: ответ — массив': (r) => {
        try { return Array.isArray(JSON.parse(r.body)); } catch { return false; }
      },
    });
    errorRate.add(!ok);
  });

  sleep(0.3);

  // 4. Публикация события — проверяем путь proxy → events-service → Kafka
  group('POST /api/events/movie', () => {
    const payload = JSON.stringify({
      movie_id: Math.floor(Math.random() * 100) + 1,
      title:    'Load Test Movie',
      action:   'viewed',
      user_id:  Math.floor(Math.random() * 10) + 1,
    });
    const res = http.post(`${BASE_URL}/api/events/movie`, payload, { headers: JSON_HEADERS });
    record(eventsTrend, res);
    const ok = check(res, {
      'event: статус 201':          (r) => r.status === 201,
      'event: status == success':   (r) => {
        try { return JSON.parse(r.body).status === 'success'; } catch { return false; }
      },
      'event: event_id присутствует': (r) => {
        try { return !!JSON.parse(r.body).event.event_id; } catch { return false; }
      },
    });
    errorRate.add(!ok);
  });

  sleep(0.5);
}

// ── Итоговый вывод ────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const passed  = Object.values(data.metrics).every(m => !m.thresholds || Object.values(m.thresholds).every(t => !t.ok === false));
  const verdict = passed ? '✅ PASSED' : '❌ FAILED';

  return {
    stdout: `
=== CinemaAbyss Load Test Summary (${scenario}) ===
${verdict}

Requests total  : ${data.metrics.http_reqs?.values?.count ?? '—'}
Failed requests : ${data.metrics.http_req_failed?.values?.passes ?? '—'}
p95 latency     : ${Math.round(data.metrics.http_req_duration?.values?.['p(95)'] ?? 0)} ms
p95 movies      : ${Math.round(data.metrics.movies_duration?.values?.['p(95)'] ?? 0)} ms
p95 events      : ${Math.round(data.metrics.events_duration?.values?.['p(95)'] ?? 0)} ms
Error rate      : ${((data.metrics.errors?.values?.rate ?? 0) * 100).toFixed(2)} %
`,
  };
}
