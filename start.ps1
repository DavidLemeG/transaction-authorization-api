# =============================================================
#  start.ps1 - Sobe a infraestrutura local e roda a aplicacao
#
#  Uso:
#    .\start.ps1              -> sobe infra e mostra como rodar a app
#    .\start.ps1 -InfraOnly   -> sobe apenas a infra
#    .\start.ps1 -Seed        -> sobe infra + repopula a fila (100k contas)
#    .\start.ps1 -Reset       -> apaga dados do Postgres e da fila antes de subir
# =============================================================

param(
    [switch]$InfraOnly,
    [switch]$Seed,
    [switch]$Reset
)

$ErrorActionPreference = "Stop"

$QueueName = "conta-bancaria-criada"
$QueueUrl = "http://localhost:4566/000000000000/conta-bancaria-criada"
$AppDir = "transaction-authorization-api"

# 1. Docker esta rodando?
Write-Host ""
Write-Host ">> Verificando Docker" -ForegroundColor Cyan
docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "   [X] Docker nao esta rodando. Abra o Docker Desktop." -ForegroundColor Red
    exit 1
}
Write-Host "   [OK] Docker ativo" -ForegroundColor Green

# 2. Sobe SOMENTE localstack e postgres
#    (o message-generator fica de fora: rodar de novo geraria +100k mensagens)
Write-Host ""
Write-Host ">> Subindo infraestrutura" -ForegroundColor Cyan
docker compose up -d localstack postgres
if ($LASTEXITCODE -ne 0) {
    Write-Host "   [X] Falha ao subir containers" -ForegroundColor Red
    exit 1
}

# 3. Aguarda health checks
function Wait-Healthy($container) {
    Write-Host "   aguardando $container" -NoNewline
    for ($i = 0; $i -lt 30; $i++) {
        $status = docker inspect --format "{{.State.Health.Status}}" $container 2>$null
        if ($status -eq "healthy") {
            Write-Host ""
            Write-Host "   [OK] $container healthy" -ForegroundColor Green
            return $true
        }
        Start-Sleep -Seconds 3
        Write-Host "." -NoNewline
    }
    Write-Host ""
    Write-Host "   [X] $container nao ficou healthy" -ForegroundColor Red
    return $false
}

Write-Host ""
Write-Host ">> Health checks" -ForegroundColor Cyan
$lsOk = Wait-Healthy "localstack"
$pgOk = Wait-Healthy "postgres-itau"

if (-not ($lsOk -and $pgOk)) {
    Write-Host "   Rode: docker compose logs" -ForegroundColor Red
    exit 1
}

# 3b. -Reset: limpa o banco e a fila antes de continuar
if ($Reset) {
    Write-Host ""
    Write-Host ">> Resetando dados (-Reset)" -ForegroundColor Cyan

    docker exec postgres-itau psql -U itau -d banking -c "TRUNCATE TABLE transactions, accounts;" | Out-Null
    Write-Host "   [OK] Postgres truncado (transactions, accounts)" -ForegroundColor Green

    $ErrorActionPreference = "Continue"
    docker exec localstack awslocal sqs delete-queue --queue-url $QueueUrl 2>&1 | Out-Null
    $ErrorActionPreference = "Stop"
    Write-Host "   [OK] Fila '$QueueName' removida (sera recriada no proximo -Seed)" -ForegroundColor Green
}

# 4. Verifica quantas mensagens tem na fila
$ErrorActionPreference = "Continue"
$raw = docker exec localstack awslocal sqs get-queue-attributes --queue-url $QueueUrl --attribute-names ApproximateNumberOfMessages 2>&1
$exitCode = $LASTEXITCODE
$ErrorActionPreference = "Stop"

if ($exitCode -ne 0) {
    Write-Host "   [!] Nao foi possivel consultar a fila." -ForegroundColor Yellow
    Write-Host "   [!] Se a app nao consumir nada, rode: .\start.ps1 -Seed" -ForegroundColor Yellow
}
else {
    try {
        $parsed = ConvertFrom-Json -InputObject ([string]::Join("", $raw))
        $count = $parsed.Attributes.ApproximateNumberOfMessages
        Write-Host "   [OK] Fila com ~$count mensagens pendentes" -ForegroundColor Green
        if ([int]$count -eq 0) {
            Write-Host "   [!] Fila vazia. Para repopular: .\start.ps1 -Seed" -ForegroundColor Yellow
        }
    }
    catch {
        Write-Host "   [!] Fila nao encontrada (localstack perdeu os dados)." -ForegroundColor Yellow
        Write-Host "   [!] Rode: .\start.ps1 -Seed" -ForegroundColor Yellow

    #         Write-Host "   [!] Fila inexistente. Repopulando automaticamente..." -ForegroundColor Yellow
    # docker compose run --rm message-generator
    }
}

# 5. Opcional: repopula a fila
if ($Seed) {
    Write-Host ""
    Write-Host ">> Gerando 100.000 contas (pode levar ~1 min)" -ForegroundColor Cyan
    docker compose run --rm message-generator
}

# 6. Infra pronta. A aplicacao roda fora deste script (IntelliJ/Maven ou Docker),
#    para manter debug facil localmente (ver ADR sobre "app fora do Docker").
Write-Host ""
Write-Host ">> Infra pronta. Para rodar a aplicacao:" -ForegroundColor Cyan
Write-Host "   - Local (debug):  cd $AppDir ; mvn spring-boot:run"
Write-Host "   - Via Docker:     docker compose --profile app up -d app"
Write-Host ""