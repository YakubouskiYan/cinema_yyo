#!/usr/bin/env bash
# =============================================================================
# ArgoCD — one-shot install + CinemaAbyss Application registration
# =============================================================================
# Usage:
#   chmod +x src/kubernetes/argocd-install.sh
#   REPO_URL=https://github.com/<YOUR_ORG>/<YOUR_REPO> ./src/kubernetes/argocd-install.sh
#
# Optional env vars:
#   ARGOCD_VERSION  — ArgoCD version to install (default: stable)
#   ARGOCD_PASSWORD — initial admin password (default: auto-generated)
# =============================================================================
set -euo pipefail

ARGOCD_NS="argocd"
ARGOCD_VERSION="${ARGOCD_VERSION:-stable}"
REPO_URL="${REPO_URL:-}"

if [[ -z "$REPO_URL" ]]; then
  echo "ERROR: set REPO_URL before running this script."
  echo "  Example: REPO_URL=https://github.com/myorg/myrepo ./argocd-install.sh"
  exit 1
fi

echo "► Creating namespace $ARGOCD_NS"
kubectl create namespace "$ARGOCD_NS" --dry-run=client -o yaml | kubectl apply -f -

echo "► Installing ArgoCD $ARGOCD_VERSION"
kubectl apply -n "$ARGOCD_NS" \
  -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"

echo "► Waiting for ArgoCD server to be ready (up to 3 minutes)..."
kubectl rollout status deployment/argocd-server -n "$ARGOCD_NS" --timeout=3m

# ── Patch argocd-server to serve over plain HTTP (useful for minikube/local) ──
# Remove this patch if you have a proper TLS termination in front of ArgoCD.
kubectl patch deployment argocd-server -n "$ARGOCD_NS" \
  --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--insecure"}]'

# ── Retrieve initial admin password ─────────────────────────────────────────
INITIAL_PWD=$(kubectl get secret argocd-initial-admin-secret \
  -n "$ARGOCD_NS" \
  -o jsonpath="{.data.password}" | base64 -d)
echo ""
echo "  ArgoCD admin password: $INITIAL_PWD"
echo "  (stored in argocd-initial-admin-secret; delete after first login)"

# ── Port-forward in background so we can run argocd CLI ─────────────────────
echo ""
echo "► Starting port-forward to ArgoCD server (localhost:8888)..."
kubectl port-forward svc/argocd-server -n "$ARGOCD_NS" 8888:80 &
PF_PID=$!
sleep 3

# ── Log in with argocd CLI ───────────────────────────────────────────────────
argocd login localhost:8888 \
  --username admin \
  --password "$INITIAL_PWD" \
  --insecure

# ── Register the repository ──────────────────────────────────────────────────
# If the repo is private, add --username / --password (PAT) here.
echo "► Registering repository: $REPO_URL"
argocd repo add "$REPO_URL" --insecure-skip-server-verification || true

# ── Patch argocd-app.yaml with the actual repo URL and apply ─────────────────
MANIFEST="$(dirname "$0")/argocd-app.yaml"
echo "► Applying Application manifest (repo=$REPO_URL)"
sed "s|https://github.com/<YOUR_ORG>/<YOUR_REPO>|$REPO_URL|g" "$MANIFEST" \
  | kubectl apply -f -

kill $PF_PID 2>/dev/null || true

echo ""
echo "════════════════════════════════════════════════════"
echo " ArgoCD is installed and CinemaAbyss app registered."
echo ""
echo " Open the UI:"
echo "   kubectl port-forward svc/argocd-server -n argocd 8888:80"
echo "   http://localhost:8888  (admin / $INITIAL_PWD)"
echo ""
echo " Watch sync status:"
echo "   argocd app get cinemaabyss"
echo "   argocd app sync cinemaabyss   # manual sync if needed"
echo "════════════════════════════════════════════════════"
