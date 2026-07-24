#!/usr/bin/env bash
# =============================================================
#  start.sh - Sobe a infraestrutura local (versao Linux/Mac do start.ps1)
#
#  Uso:
#    ./start.sh              -> sobe infra e mostra como rodar a app
#    ./start.sh --infra-only -> sobe apenas a infra
#    ./start.sh --seed       -> sobe infra + repopula a fila (100k contas)
#    ./start.sh --reset      -> apaga dados do Postgres e da fila antes de subir
#    (flags podem ser combinadas, ex.: ./start.sh --reset --seed)
# =============================================================
set -euo pipefail

QUEUE_NAME="conta-bancaria-criada"
QUEUE_URL="http://localhost:4566/000000000000/conta-bancaria-criada"
APP_DIR="transaction-authorization-api"

INFRA_ONLY=false
SEED=false
RESET=false

for arg in "$@"; do
  case "$arg" in
    --infra-only) INFRA_ONLY=true ;;
    --seed) SEED=true ;;
    --reset) RESET=true ;;
    *) echo "Flag desconhecida: $arg" >&2; exit 1 ;;
  esac
done

wait_healthy() {
  local container=$1
  echo -n "   aguardando $container"
  for _ in $(seq 1 30); do
    status=$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || echo "")
    if [ "$status" = "healthy" ]; then
      echo ""
      echo "   [OK] $container healthy"
      return 0
    fi
    sleep 3
    echo -n "."
  done
  echo ""
  echo "   [X] $container nao ficou healthy"
  return 1
}

echo ""
echo ">> Verificando Docker"
if ! docker info > /dev/null 2>&1; then
  echo "   [X] Docker nao esta rodando. Abra o Docker Desktop."
  exit 1
fi
echo "   [OK] Docker ativo"

echo ""
echo ">> Subindo infraestrutura"
docker compose up -d localstack postgres

echo ""
echo ">> Health checks"
wait_healthy "localstack" && LS_OK=true || LS_OK=false
wait_healthy "postgres-itau" && PG_OK=true || PG_OK=false

if [ "$LS_OK" != true ] || [ "$PG_OK" != true ]; then
  echo "   Rode: docker compose logs"
  exit 1
fi

if [ "$RESET" = true ]; then
  echo ""
  echo ">> Resetando dados (--reset)"
  docker exec postgres-itau psql -U itau -d banking -c "TRUNCATE TABLE transactions, accounts;" > /dev/null
  echo "   [OK] Postgres truncado (transactions, accounts)"
  docker exec localstack awslocal sqs delete-queue --queue-url "$QUEUE_URL" > /dev/null 2>&1 || true
  echo "   [OK] Fila '$QUEUE_NAME' removida (sera recriada no proximo --seed)"
fi

RAW=$(docker exec localstack awslocal sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names ApproximateNumberOfMessages 2>&1) || true
if echo "$RAW" | grep -q "ApproximateNumberOfMessages"; then
  COUNT=$(echo "$RAW" | grep -oE '"ApproximateNumberOfMessages": "[0-9]+"' | grep -oE '[0-9]+')
  echo "   [OK] Fila com ~$COUNT mensagens pendentes"
  if [ "$COUNT" = "0" ]; then
    echo "   [!] Fila vazia. Para repopular: ./start.sh --seed"
  fi
else
  echo "   [!] Fila nao encontrada (localstack perdeu os dados)."
  echo "   [!] Rode: ./start.sh --seed"
fi

if [ "$SEED" = true ]; then
  echo ""
  echo ">> Gerando 100.000 contas (pode levar ~1 min)"
  docker compose run --rm message-generator
fi

if [ "$INFRA_ONLY" = true ]; then
  echo ""
  echo ">> Infra pronta. App nao iniciada (--infra-only)."
  echo "   Para rodar: cd $APP_DIR && mvn spring-boot:run"
  echo ""
  exit 0
fi

echo ""
echo ">> Infra pronta. Para rodar a aplicacao:"
echo "   - Local (debug):  cd $APP_DIR && mvn spring-boot:run"
echo "   - Via Docker:     docker compose --profile app up -d app"
echo ""
