# Architecture Decision Records

Registro das decisões técnicas relevantes do projeto, formato
[ADR](https://github.com/joelparkerhenderson/architecture-decision-record).

| ADR | Decisão |
|---|---|
| [0001](0001-java17-spring-boot3.md) | Java 17 + Spring Boot 3.3.5 |
| [0002](0002-postgresql.md) | PostgreSQL como banco de dados |
| [0003](0003-rich-domain-model.md) | Rich Domain Model |
| [0004](0004-pessimistic-lock.md) | Lock pessimista (vs otimista) na conta |
| [0005](0005-package-by-feature.md) | Package by feature |
| [0006](0006-aws-sdk-v2.md) | AWS SDK v2 puro (vs Spring Cloud AWS) |
| [0007](0007-bigdecimal-money.md) | BigDecimal para valores monetários |
| [0008](0008-utc-storage.md) | UTC no banco, fuso horário só na borda |
| [0009](0009-http-200-refused-transaction.md) | HTTP 200 para transação recusada por saldo insuficiente |
| [0010](0010-api-idempotency.md) | Idempotência via transactionId |
| [0011](0011-resilience4j.md) | Resilience4j: retry com full jitter + circuit breaker |
| [0012](0012-flyway.md) | Flyway para versionamento de schema |
| [0013](0013-idempotent-replay-balance-snapshot.md) | Reenvio idempotente mostra o saldo da época da transação, não o saldo atual |
| [0014](0014-transaction-persistable-requires-new.md) | Transaction como Persistable + TransactionRecorder com REQUIRES_NEW (corrige corrida de idempotência) |

Decisões relacionadas a deploy/infraestrutura, com o mesmo nível de detalhe,
estão em documentos dedicados por serem melhor representadas com diagramas:
- [Diagrama de deploy em cloud](../diagrams/cloud-deployment.md)
- [Proposta de pipeline CI/CD (canary vs blue/green)](../ci-cd-pipeline.md)
