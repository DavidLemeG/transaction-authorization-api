# ADR 0006 — AWS SDK v2 puro (vs Spring Cloud AWS)

## Status
Aceito

## Contexto
O consumo da fila `conta-bancaria-criada` precisa de controle fino sobre
long polling, tamanho de lote, ack manual (delete só após persistir a conta) e
shutdown gracioso — para não perder nem duplicar contas sob volumetria alta
(100.000 mensagens).

## Decisão
`software.amazon.awssdk:sqs` (AWS SDK v2) usado diretamente em
`AccountCreatedListener`, com um pool de threads próprio
(`app.aws.consumer-threads`) fazendo `receiveMessage`/`deleteMessageBatch`
manualmente, em vez de um listener declarativo de framework.

## Consequências
- Controle total sobre o ciclo de vida do consumo: `@EventListener(ApplicationReadyEvent.class)`
  inicia N threads, `@PreDestroy` sinaliza parada e aguarda até 30s pelo
  término gracioso das threads em andamento.
- Ack manual: a mensagem só é deletada da fila depois que
  `AccountService.createIfAbsent` persiste a conta com sucesso — falha em uma
  mensagem do lote não derruba as demais (cada mensagem é tratada
  individualmente dentro do lote).
- Mais código de infraestrutura escrito à mão (polling loop, retry, circuit
  breaker — ver ADR 0010) do que se um framework de mensageria fizesse isso.

## Alternativas consideradas
- **Spring Cloud AWS Messaging**: mais declarativo (`@SqsListener`), porém a
  geração atual desse projeto é majoritariamente voltada a Spring Boot 2.x;
  usar SDK v2 puro evita depender de uma camada de abstração com paridade
  incerta em relação às features do SDK v2 (endpoint override para LocalStack,
  batch delete, etc.), que já precisávamos usar diretamente de qualquer forma.
