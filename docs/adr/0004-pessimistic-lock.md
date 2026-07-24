# ADR 0004 — Lock pessimista (vs otimista) na conta

## Status
Aceito

## Contexto
Duas requisições de débito podem chegar simultaneamente para a mesma conta.
Sem controle de concorrência, ambas poderiam ler o mesmo saldo antes de
qualquer uma escrever, aprovando dois débitos que juntos deixariam o saldo
negativo (lost update).

## Decisão
Lock pessimista via `SELECT ... FOR UPDATE`
(`AccountRepository.findByIdForUpdate`, `@Lock(LockModeType.PESSIMISTIC_WRITE)`).
A segunda transação concorrente na mesma conta bloqueia até a primeira
commitar, e então lê o saldo já atualizado.

## Consequências
- Serialização automática de débitos/créditos concorrentes **na mesma conta**;
  contas diferentes continuam processando em paralelo (o lock é por linha, não
  por tabela).
- Provado com um teste real contra Postgres via Testcontainers
  (`ConcurrentDebitTest`): 10 threads tentando debitar 80 de uma conta com
  saldo 100 → exatamente 1 aprovada, saldo final 20, nunca negativo.
- Trade-off aceito: sob alta contenção na mesma conta, requisições esperam na
  fila do lock em vez de falhar e re-tentar. Para o padrão de uso esperado
  (poucas transações simultâneas por conta individual, mesmo com alta
  volumetria agregada no sistema), isso é preferível a otimista.

## Alternativas consideradas
- **Lock otimista** (coluna `version`, `@Version` do JPA): evita bloqueio,
  mas exige que o chamador trate `OptimisticLockException` com retry — mais
  lógica de retry na camada de aplicação, e sob alta contenção na mesma conta
  geraria muitas colisões e re-tentativas, com pior previsibilidade de latência
  do que uma fila de lock simples. Otimista tende a compensar quando colisões
  são raras; aqui não temos garantia disso (o mesmo cliente pode disparar
  múltiplas transações rápidas na mesma conta).
