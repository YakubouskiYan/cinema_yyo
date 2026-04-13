# CinemaAbyss — Руководство по развёртыванию

Это руководство описывает все файлы инфраструктуры и ручные шаги, необходимые для запуска проекта в Kubernetes.

---

## Структура директории

```
src/kubernetes/
├── helm/                        # Helm chart — основной способ деплоя
│   ├── Chart.yaml               # Метаданные чарта
│   ├── values.yaml              # Все настройки (точка входа для конфигурации)
│   └── templates/               # Шаблоны Kubernetes-ресурсов
│       ├── configmap.yaml       # Конфиг Strangler Fig (проценты миграции)
│       ├── secret.yaml          # Пароль к БД
│       ├── dockerconfigsecret.yaml  # Pull secret для GHCR
│       ├── ingress.yaml         # Входящий трафик + TLS
│       ├── istio.yaml           # mTLS + Circuit Breaker + VirtualService
│       ├── namespace.yaml       # Неймспейс cinemaabyss
│       ├── postgres-init-configmap.yaml  # SQL-схема БД
│       ├── kafka/
│       │   └── kafka.yaml       # Kafka + Zookeeper
│       └── services/
│           ├── monolith.yaml    # Deployment + Service монолита
│           ├── movies-service.yaml  # Deployment + Service Movies
│           ├── events-service.yaml  # Deployment + Service Events
│           ├── proxy-service.yaml   # Deployment + Service API Gateway
│           └── postgres.yaml    # StatefulSet + Service PostgreSQL
├── kafka/
│   └── kafka.yaml               # Raw-манифест Kafka (без Helm, для справки)
├── argocd-app.yaml              # Объявление приложения для ArgoCD
├── argocd-install.sh            # Скрипт установки ArgoCD в кластер
├── circuit-breaker-config.yaml  # Raw-манифест Istio Circuit Breaker (без Helm)
├── configmap.yaml               # Raw ConfigMap (без Helm, для справки)
├── dockerconfigsecret.yaml      # Raw pull secret (без Helm, для справки)
├── events-service.yaml          # Raw Events Service (без Helm, для справки)
├── ingress.yaml                 # Raw Ingress (без Helm, для справки)
├── monolith.yaml                # Raw Monolith (без Helm, для справки)
├── movies-service.yaml          # Raw Movies Service (без Helm, для справки)
├── namespace.yaml               # Raw Namespace (без Helm, для справки)
├── postgres-init-configmap.yaml # Raw DB init (без Helm, для справки)
├── postgres.yaml                # Raw PostgreSQL (без Helm, для справки)
├── proxy-service.yaml           # Raw Proxy Service (без Helm, для справки)
└── secret.yaml                  # Raw Secret (без Helm, для справки)
```

> Raw-файлы в корне `src/kubernetes/` — это исходные манифесты для ручного применения через `kubectl apply`. Helm chart в `helm/` является основным способом деплоя и включает те же ресурсы, параметризованные через `values.yaml`.

---

## Описание ключевых файлов

### [helm/values.yaml](helm/values.yaml)

Единственный файл, который нужно трогать при деплое. Содержит:

- **imagePullSecrets** — учётные данные для скачивания образов из GHCR. Реальное значение не хранится в git (см. раздел про секреты ниже)
- **database** — хост, порт, имя БД, пароль
- **monolith / moviesService / proxyService / eventsService** — образ, количество реплик, лимиты CPU/RAM
- **kafka / zookeeper** — образ, хранилище
- **ingress** — домен, TLS, аннотации nginx и cert-manager
- **config.moviesMigrationPercent** — процент трафика `/api/movies` на Movies Service (Strangler Fig)
- **istio.enabled** — включить/выключить mTLS и Circuit Breaker

### [helm/templates/istio.yaml](helm/templates/istio.yaml)

Создаётся только если `istio.enabled: true`. Содержит:

- `PeerAuthentication` — принудительный mTLS внутри неймспейса. Все соединения между сервисами шифруются, plain HTTP запрещён
- `DestinationRule` (movies-service) — жёсткий Circuit Breaker: 1 соединение, срабатывает после 1 ошибки, выключает сервис на 3 минуты
- `DestinationRule` (monolith) — мягкий Circuit Breaker: 10 соединений, срабатывает после 5 ошибок, выключает на 30 секунд
- `VirtualService` — таймауты (10s / 15s) и 3 попытки retry для обоих сервисов

### [circuit-breaker-config.yaml](circuit-breaker-config.yaml)

Raw-версия Istio Circuit Breaker — те же DestinationRule и VirtualService, что и в Helm, но для ручного применения через `kubectl apply`. Содержит подробные комментарии к каждому параметру.

### [argocd-app.yaml](argocd-app.yaml)

Описывает приложение для ArgoCD. После применения ArgoCD начинает следить за веткой `main` в репозитории и автоматически деплоит при каждом изменении `values.yaml` (CI обновляет теги образов). Включён self-heal — ручные изменения через `kubectl` будут автоматически откатываться.

### [argocd-install.sh](argocd-install.sh)

Скрипт для первоначальной установки ArgoCD и регистрации приложения. Запускается один раз.

---

## Порядок развёртывания

### Шаг 1 — Поднять кластер

Выбери один из вариантов:

```bash
# Minikube (локально)
minikube start --cpus=4 --memory=8192

# k3s (лёгкий продакшн)
curl -sfL https://get.k3s.io | sh -

# Облако — используй консоль EKS / GKE / AKS
```

---

### Шаг 2 — Установить cert-manager

Выпускает и автоматически обновляет TLS-сертификаты для Ingress через Let's Encrypt.

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml

# Дождаться готовности
kubectl rollout status deployment/cert-manager -n cert-manager --timeout=3m
```

---

### Шаг 3 — Создать ClusterIssuer для Let's Encrypt

Говорит cert-manager где получать сертификаты. Создаётся один раз на кластер.

```bash
kubectl apply -f - <<EOF
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: your@email.com        # замени на свой email
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
      - http01:
          ingress:
            class: nginx
EOF
```

---

### Шаг 4 — Установить Istio

Нужен для mTLS между сервисами и Circuit Breaker. Устанавливается один раз на кластер.

```bash
# Скачать istioctl
curl -L https://istio.io/downloadIstio | sh -
export PATH="$PWD/istio-*/bin:$PATH"

# Установить Istio с профилем по умолчанию
istioctl install --set profile=default -y

# Дождаться готовности
kubectl rollout status deployment/istiod -n istio-system --timeout=3m
```

---

### Шаг 5 — Установить ArgoCD (GitOps, опционально)

ArgoCD следит за репозиторием и деплоит автоматически при каждом push в `main`.

```bash
# Установить ArgoCD через скрипт (подставь URL своего репозитория)
chmod +x src/kubernetes/argocd-install.sh
REPO_URL=https://github.com/<YOUR_ORG>/<YOUR_REPO> ./src/kubernetes/argocd-install.sh

# Открыть UI ArgoCD
kubectl port-forward svc/argocd-server -n argocd 8888:80
# Перейти: http://localhost:8888
```

Если ArgoCD не нужен — деплой выполняется вручную через `helm upgrade` (см. Шаг 7).

---

### Шаг 6 — Создать pull secret для GHCR

Kubernetes нужны учётные данные чтобы скачать образы из приватного реестра GHCR.

**Секрет не хранится в git.** Создаётся вручную один раз:

```bash
# Способ 1 — из локального docker config (рекомендуется)
kubectl create namespace cinemaabyss  # если ещё не создан

kubectl create secret generic dockerconfigjson \
  --from-file=.dockerconfigjson=$HOME/.docker/config.json \
  --type=kubernetes.io/dockerconfigjson \
  -n cinemaabyss
```

Если `~/.docker/config.json` не содержит авторизацию в `ghcr.io` — сначала войди:

```bash
echo <YOUR_GITHUB_PAT> | docker login ghcr.io -u <YOUR_GITHUB_USERNAME> --password-stdin
```

---

### Шаг 7 — Задеплоить через Helm

```bash
# Способ A — если установлен ArgoCD (деплой автоматический, делать ничего не нужно)
# ArgoCD сам подхватит chart из ветки main

# Способ B — ручной деплой
helm upgrade --install cinemaabyss src/kubernetes/helm \
  --namespace cinemaabyss \
  --create-namespace

# Если pull secret ещё не создан вручную (Способ 2 из Шага 6)
helm upgrade --install cinemaabyss src/kubernetes/helm \
  --namespace cinemaabyss \
  --create-namespace \
  --set imagePullSecrets.dockerconfigjson="$(cat ~/.docker/config.json | base64 -w0)"
```

---

### Шаг 8 — Включить Istio mTLS и Circuit Breaker

По умолчанию `istio.enabled: false`. Включается отдельно после того как Istio установлен и поды перезапущены.

```bash
# 1. Пересоздать поды с Istio sidecar (Helm уже добавил лейбл на неймспейс)
kubectl rollout restart deployment -n cinemaabyss

# 2. Убедиться что sidecar внедрён (в колонке READY должно быть 2/2)
kubectl get pods -n cinemaabyss

# 3. Задеплоить с включённым Istio
helm upgrade cinemaabyss src/kubernetes/helm \
  --namespace cinemaabyss \
  --set istio.enabled=true

# 4. Проверить что ресурсы Istio созданы
kubectl get peerauthentication,destinationrule,virtualservice -n cinemaabyss
```

---

## Секреты — важно

| Что | Где хранится | Как передаётся в кластер |
|---|---|---|
| Пароль БД | `values.yaml` (base64) | Helm создаёт `Secret/cinemaabyss-secrets` |
| Pull secret GHCR | **НЕ в git** | Вручную через `kubectl create secret` (Шаг 6) |
| TLS-сертификат | **НЕ в git** | cert-manager выпускает автоматически |

Файлы с локальными секретами можно называть `values.secret.yaml` или `values.*.secret.yaml` — они добавлены в `.gitignore` и никогда не попадут в репозиторий.

---

## Проверка после деплоя

```bash
# Все поды запущены
kubectl get pods -n cinemaabyss

# Ingress получил IP
kubectl get ingress -n cinemaabyss

# Сертификат выпущен cert-manager
kubectl get certificate -n cinemaabyss

# Istio ресурсы (если включён)
kubectl get peerauthentication,destinationrule,virtualservice -n cinemaabyss

# Статус ArgoCD (если используется)
argocd app get cinemaabyss
```
