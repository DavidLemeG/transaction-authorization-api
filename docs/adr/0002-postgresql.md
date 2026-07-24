# ADR 0002 — PostgreSQL como banco de dados

## Status
Aceito

## Contexto
A aplicação manipula saldo de contas bancárias sob concorrência: múltiplas
requisições de débito/crédito podem chegar para a mesma conta ao mesmo tempo,
e a regra central ("saldo nunca pode ficar negativo") depende de leitura e
escrita atômicas do saldo.

## Decisão
PostgreSQL 16 como banco relacional primário, com Flyway para versionamento de
schema e lock pessimista (`SELECT ... FOR UPDATE`, ver ADR 0004) nas operações
de débito/crédito.

## Consequências
- ACID completo: a transação de débito (ler saldo → validar → gravar novo saldo
  → gravar a transação) acontece dentro de uma única transação de banco
  (`@Transactional` em `TransactionService.authorize`).
- `NUMERIC(19,2)` no schema, mapeado para `BigDecimal` no domínio (ver ADR 0007) —
  sem erro de arredondamento binário.
- Índices em `transactions(account_id)` e `transactions(created_at)` já
  preparados para consultas de extrato/auditoria futuras.
- Testado sob concorrência real com Testcontainers
  (`ConcurrentDebitTest`), não apenas H2 — H2 não implementa
  `SELECT ... FOR UPDATE` com a mesma semântica de lock de linha do Postgres.

## Alternativas consideradas
- **Banco NoSQL (DynamoDB, MongoDB)**: ofereceriam escalabilidade horizontal
  mais simples, mas exigiriam reimplementar controle de concorrência otimista
  ou transações condicionais na aplicação — mais código e mais superfície para
  bugs numa regra de negócio financeira crítica. Um banco relacional com lock
  pessimista nativo é a escolha mais direta para "nunca deixar o saldo ficar
  negativo sob concorrência".
- **MySQL**: alternativa relacional viável; Postgres foi preferido por suporte
  mais maduro a `SELECT ... FOR UPDATE` e por ser o padrão de fato em
  ambientes Spring Boot + AWS (RDS PostgreSQL).
