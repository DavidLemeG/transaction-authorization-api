# API de Autorização de Transações — Itaú Unibanco (Desafio Técnico)

API que autoriza operações de **crédito** e **débito** em contas bancárias.
As contas são criadas de forma assíncrona a partir de mensagens publicadas em
uma fila **AWS SQS** (`conta-bancaria-criada`) por um sistema externo de
abertura de contas; a API expõe `POST /transactions/{transactionId}` para
autorizar crédito/débito, com a regra central de que **um débito que deixaria
o saldo negativo é sempre recusado, sem alterar o saldo**.

## Sumário
- [Arquitetura](#arquitetura)
- [Stack](#stack)
- [Como rodar localmente](#como-rodar-localmente)
- [Como testar](#como-testar)
- [Decisões técnicas](#decisões-técnicas)
- [Observabilidade](#observabilidade)
- [Diagramas e pipeline](#diagramas-e-pipeline)
- [O que faria com mais tempo](#o-que-faria-com-mais-tempo)

## Arquitetura

```
Abertura de contas (externo) --> Fila SQS --> [Consumidor SQS] --> Postgres
                                                       |
Cliente --> POST /transactions/{transactionId} --> [API] --> Postgres
```

- `AccountCreatedListener` consome a fila com N threads configuráveis
  (`app.aws.consumer-threads`), long polling de 20s, lotes de 10 mensagens,
  ack manual (delete só após persistir com sucesso) e idempotência
  (`existsById` antes de criar a conta).
- `TransactionController` recebe a autorização, `TransactionService` aplica a
  regra de negócio dentro de uma transação de banco com lock pessimista na
  conta (`SELECT ... FOR UPDATE`), garantindo que débitos concorrentes na
  mesma conta nunca deixem o saldo negativo.

### Estrutura de pacotes (package by feature — ver [ADR 0005](docs/adr/0005-package-by-feature.md))

```
com.itau.transaction/
  domain/
    account/        Account (rich model), AccountStatus
    transaction/     Transaction, TransactionType, TransactionStatus
    exception/       AccountNotFoundException, InsufficientFundsException
  repository/        AccountRepository (findByIdForUpdate), TransactionRepository
  service/           TransactionService, AccountService, AuthorizationCommand, AuthorizationResult
  api/               TransactionController, OpenApiConfig
    dto/             TransactionRequest, TransactionResponse
    exception/       GlobalExceptionHandler (ProblemDetail / RFC 7807)
  messaging/         AccountCreatedListener
    dto/             AccountCreatedEvent
    config/          SqsConfig, SqsProperties, ResilienceConfig
```

## Stack

- Java 17 + Spring Boot 3.3.5
- PostgreSQL 16 + Flyway
- AWS SDK v2 (SQS) + LocalStack para desenvolvimento local
- Resilience4j (retry com backoff exponencial + full jitter, circuit breaker)
- springdoc-openapi (Swagger UI)
- Micrometer + Prometheus
- JUnit 5, Mockito, Testcontainers, JaCoCo

Justificativas completas de cada escolha estão nos [ADRs](docs/adr/).

## Como rodar localmente

**Pré-requisitos:** Docker Desktop, Java 17, Maven (ou use o `mvnw` incluído).

### 1. Subir a infraestrutura (Postgres + LocalStack/SQS)

Windows (PowerShell):
```powershell
.\start.ps1 -Seed
```

Linux/Mac:
```bash
./start.sh --seed
```

Isso sobe `localstack` e `postgres` via `docker-compose.yml`, aguarda os
health checks, e popula a fila com 100.000 contas sintéticas
(`--seed`/`-Seed`).

Outras flags: `-InfraOnly`/`--infra-only` (só sobe a infra, sem instruções
de app), `-Reset`/`--reset` (limpa Postgres e a fila antes de subir).

> ⚠️ **LocalStack (community) não persiste dados.** Toda vez que o container
> para, a fila e as 100k mensagens somem. Ao voltar ao projeto, rode de novo
> com `-Seed`/`--seed`, ou diretamente: `docker compose run --rm message-generator`.
>
> ⚠️ **Nunca rode `docker compose up` sem especificar os serviços** — isso
> reexecuta o `message-generator` e duplica as 100k mensagens na fila. Use
> sempre `docker compose up -d localstack postgres` (é o que os scripts acima fazem).

### 2. Rodar a aplicação

**Localmente (debug fácil, IntelliJ ou Maven):**
```bash
cd transaction-authorization-api
mvn spring-boot:run
```

**Via Docker (multi-stage `Dockerfile`, mesma rede dos demais containers):**
```bash
docker compose --profile app up -d app
```
(o serviço `app` fica atrás de um profile do Compose para não iniciar sozinho
com um `docker compose up` simples — ver a armadilha do `message-generator` acima)

A API sobe em `http://localhost:8080`.

### Credenciais e endpoints locais
- Postgres: `itau`/`itau`, banco `banking`, porta `5432`
- LocalStack: `http://localhost:4566`, região `sa-east-1`, credenciais `test`/`test`
- Fila: `conta-bancaria-criada`

## Como testar

### Testes automatizados
```bash
cd transaction-authorization-api
mvn clean test
```
53 testes: unitários de domínio (`AccountTest`, sem Spring), Mockito
(`TransactionServiceTest`, `TransactionRecorderTest`, `AccountServiceTest`,
`AccountCreatedListenerTest`), mapeamento de DTO (`TransactionResponseTest`),
API via MockMvc (`TransactionControllerTest`), um teste de integração HTTP
de ponta a ponta (`TransactionHttpIntegrationTest`) e dois testes de
concorrência contra PostgreSQL real via Testcontainers (`ConcurrentDebitTest`):
- 10 threads debitando 80 simultaneamente de uma conta com saldo 100 → só uma
  aprovada, saldo final nunca negativo (prova o lock pessimista).
- 10 threads enviando a **mesma** `transactionId` simultaneamente → só uma
  processa o crédito, as demais recebem a resposta idempotente da vencedora
  (prova a correção do [ADR 0014](docs/adr/0014-transaction-persistable-requires-new.md)).

`AccountCreatedListenerTest` testa o consumidor SQS sem threads reais nem
LocalStack: `SqsClient`/`AccountService` são mocks, mas `Retry`/`CircuitBreaker`
são instâncias reais do Resilience4j (configuradas para serem rápidas em
teste) — prova que o retry de fato tenta de novo após falha transitória, e
que o circuit breaker realmente para de processar o resto do lote quando abre
no meio de um lote de mensagens.

`TransactionHttpIntegrationTest` é o único teste que não mocka nada: requisição
HTTP real (`TestRestTemplate`) → `TransactionController` → `TransactionService`/
`TransactionRecorder` → repositórios → PostgreSQL real (Testcontainers),
cobrindo os 3 cenários do enunciado, idempotência e conta inexistente
exatamente como um cliente real chamaria a API.

Relatório de cobertura (JaCoCo) gerado em
`transaction-authorization-api/target/site/jacoco/index.html` — **85% de
instruções cobertas no total** (meta era 85%). Domínio, service, API e DTO
ficam entre 97–100%; o pacote `messaging` foi de 25% para ~63% depois do
`AccountCreatedListenerTest` — o que resta ali (`runConsumer`/`pollLoop`/
`start`/`stop`, os laços `while(running.get())` de orquestração de threads)
não tem teste de unidade dedicado por ser controle de thread de baixo valor
de teste isolado, não lógica de negócio — ver
[O que faria com mais tempo](#o-que-faria-com-mais-tempo).

### Testando a API manualmente
Coleção pronta em [`transaction-authorization-api/requests.http`](transaction-authorization-api/requests.http)
(formato nativo do IntelliJ HTTP Client — funciona também colando os `curl`
equivalentes em qualquer terminal). Cobre os 3 cenários do enunciado (crédito
aprovado, débito aprovado, débito recusado) mais idempotência e corner cases
de validação.

Exemplo rápido:
```bash
curl -X POST http://localhost:8080/transactions/8e8ae808-b154-48b5-9f3e-553935cc4543 \
  -H "Content-Type: application/json" \
  -d '{"accountId":"<uuid-de-uma-conta-existente>","type":"CREDIT","amount":{"value":100.00,"currency":"BRL"}}'
```

> Para pegar um `accountId` real após popular a fila:
> `docker exec postgres-itau psql -U itau -d banking -c "SELECT id FROM accounts LIMIT 1;"`

### Swagger UI
`http://localhost:8080/swagger-ui.html` (spec JSON em `/v3/api-docs`).

## Decisões técnicas

Registradas como ADRs em [`docs/adr/`](docs/adr/), com contexto, decisão,
consequências e alternativas consideradas para cada uma:

1. [Java 17 + Spring Boot 3.3.5](docs/adr/0001-java17-spring-boot3.md)
2. [PostgreSQL como banco de dados](docs/adr/0002-postgresql.md)
3. [Rich Domain Model](docs/adr/0003-rich-domain-model.md)
4. [Lock pessimista (vs otimista)](docs/adr/0004-pessimistic-lock.md)
5. [Package by feature](docs/adr/0005-package-by-feature.md)
6. [AWS SDK v2 puro (vs Spring Cloud AWS)](docs/adr/0006-aws-sdk-v2.md)
7. [BigDecimal para dinheiro](docs/adr/0007-bigdecimal-money.md)
8. [UTC no banco, fuso na borda](docs/adr/0008-utc-storage.md)
9. [HTTP 200 para transação recusada](docs/adr/0009-http-200-refused-transaction.md)
10. [Idempotência via transactionId](docs/adr/0010-api-idempotency.md)
11. [Resilience4j: retry com full jitter + circuit breaker](docs/adr/0011-resilience4j.md)
12. [Flyway para versionamento de schema](docs/adr/0012-flyway.md)
13. [Reenvio idempotente mostra o saldo da época da transação](docs/adr/0013-idempotent-replay-balance-snapshot.md)
14. [Transaction como Persistable + TransactionRecorder com REQUIRES_NEW](docs/adr/0014-transaction-persistable-requires-new.md)

## Observabilidade

- **Health:** `GET /actuator/health` (inclui status do circuit breaker `db-persistence`)
- **Métricas Prometheus:** `GET /actuator/prometheus`, incluindo:
  - `transaction_processed_total{type,status}` — contagem de transações por tipo e resultado
  - `transaction_authorization_latency_seconds` — latência de `TransactionService.authorize`
  - `sqs_messages_consumed_total` / `sqs_messages_failed_total` — throughput/erros do consumidor SQS
  - métricas padrão do Resilience4j (`resilience4j_circuitbreaker_*`, `resilience4j_retry_*`)
- **Logs:** sinaliza quando a fila é encontrada (recuperação de
  `QueueDoesNotExistException`) e quando o primeiro lote de mensagens é
  recebido, além do throughput agregado a cada 10.000 contas processadas e um
  resumo final quando a fila drena; transições de estado do circuit breaker
  logadas em `WARN`.

## Diagramas e pipeline

- [Diagrama de deploy em cloud pública (AWS)](docs/diagrams/cloud-deployment.md)
- [Proposta de pipeline CI/CD — canary release](docs/ci-cd-pipeline.md)

## O que faria com mais tempo

Itens conscientemente deixados como melhoria futura, com o motivador de cada um:

- **DLQ (Dead Letter Queue) real no `docker-compose.yml`**: hoje uma mensagem
  corrompida na fila SQS entraria em loop de reentrega indefinido. O padrão
  correto é uma redrive policy para uma DLQ após N tentativas — não configurado
  no LocalStack deste projeto por não ser o foco do desafio, mas está no
  [diagrama de deploy](docs/diagrams/cloud-deployment.md) como parte da
  arquitetura de produção.
- **Cache de leitura (Redis)** para consulta de saldo em alta escala, caso um
  endpoint de consulta (fora do escopo do desafio) seja adicionado.
- **Particionamento/sharding** da tabela `transactions` por data, relevante
  quando o volume de transações crescer ordens de magnitude além do teste
  atual (100k contas).
- **Event sourcing do saldo** (saldo como projeção do histórico de transações
  em vez de coluna mutável) — mudança arquitetural maior, fora do escopo de
  uma entrega de uma semana.
- **Autenticação/autorização** (OAuth2 client credentials ou mTLS entre
  serviços) — a API hoje não tem nenhuma proteção de acesso.
- **Rate limiting por cliente** — hoje só há throttling genérico se aplicado
  na camada de API Gateway (ver diagrama de deploy), nada na aplicação.
- **Observabilidade distribuída (OpenTelemetry/tracing)** — os logs e métricas
  atuais são suficientes para uma instância isolada, mas não correlacionam
  uma requisição através de múltiplas instâncias/serviços.
- **Teste da orquestração de threads do `AccountCreatedListener`**: a lógica
  de negócio do consumidor (`processBatch`, retry, circuit breaker) já tem
  teste dedicado (`AccountCreatedListenerTest`); o que resta são os laços
  `while(running.get())` de `runConsumer`/`pollLoop` e o `start()`/`stop()` —
  testar isso de verdade exigiria rodar threads reais com timing controlado,
  custo alto para o valor marginal (é orquestração, não regra de negócio).

## Regras do enunciado

- ❌ PDF do desafio não está neste repositório.
- ✅ Repositório público no GitHub.
- ✅ `docker-compose.yml` na raiz com todas as dependências necessárias
  (LocalStack/SQS, Postgres, gerador de mensagens, e a própria aplicação via
  `--profile app`).
