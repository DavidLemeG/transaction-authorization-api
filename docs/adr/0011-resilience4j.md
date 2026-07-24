# ADR 0011 — Resilience4j: retry com full jitter + circuit breaker

## Status
Aceito

## Contexto
O enunciado pede explicitamente padrões de resiliência: "retries, backoff,
full jitter, circuit breaker". O ponto de maior risco de falha transitória na
aplicação é a comunicação com o SQS (rede, throttling) e, secundariamente, a
persistência no Postgres durante o consumo em massa de 100.000 mensagens.

## Decisão
Duas instâncias nomeadas, registradas nos registries auto-configurados pelo
`resilience4j-spring-boot3` (visíveis em `/actuator/health` e
`/actuator/prometheus`), definidas em `ResilienceConfig`:

- **Retry `sqs`**: até 5 tentativas, `IntervalFunction.ofExponentialRandomBackoff`
  (backoff exponencial combinado com jitter completo — cada tentativa espera um
  valor aleatório entre 0 e o teto exponencial da tentativa, não apenas um
  valor exponencial fixo). Aplicado em `resolveQueueUrl`, `receiveMessages` e
  `deleteBatch` no `AccountCreatedListener`.
- **Circuit breaker `db-persistence`**: janela de 20 chamadas, mínimo de 10
  para calcular a taxa, abre acima de 50% de falha, 15s em aberto antes de
  testar novamente (half-open). Envolve a chamada
  `accountService.createIfAbsent(event)` dentro do loop de processamento do
  lote SQS.

## Consequências
- Falhas transitórias de rede com o SQS (throttling do LocalStack/AWS,
  timeout pontual) se recuperam sozinhas sem intervenção, sem martelar a fila
  em lockstep entre as 4 threads consumidoras (é isso que o jitter evita).
- Se o Postgres cair durante o consumo em massa: o circuito abre, o listener
  **para de tentar persistir contas e para de deletar mensagens da fila**
  (mensagens não processadas continuam na fila para reentrega) — evita o pior
  cenário, que seria deletar mensagens sem ter persistido a conta
  correspondente. Half-open após 15s testa se o banco voltou, sem exigir
  reinício da aplicação.
- Transições de estado do circuit breaker são logadas (`onStateTransition`) e
  aparecem em `/actuator/health` (`management.health.circuitbreakers.enabled: true`).

## Alternativas consideradas
- **Anotações `@Retry`/`@CircuitBreaker` do Resilience4j**: mais declarativo,
  mas dependem de proxy AOP do Spring — não funcionam em chamadas internas
  (self-invocation) dentro da mesma classe, que é exatamente o caso aqui
  (`AccountCreatedListener` chama seus próprios métodos privados de polling).
  Por isso a API programática (`Retry.decorateSupplier`,
  `CircuitBreaker.decorateRunnable`) foi usada diretamente.
