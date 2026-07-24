# ADR 0014 — Transaction como Persistable + TransactionRecorder com REQUIRES_NEW

## Status
Aceito

## Contexto
O [ADR 0010](0010-api-idempotency.md) já registrava, como melhoria conhecida,
que duas requisições **verdadeiramente concorrentes** com o mesmo
`transactionId` poderiam colidir. A suposição documentada ali era que a
segunda thread quebraria com `DataIntegrityViolationException` (violação de
chave primária) — um erro "seguro" (a transação daquela thread seria revertida).

Uma investigação empírica (5 threads reais, Testcontainers, mesma
`transactionId`, mesmo crédito de 100) mostrou que **isso não é o que
acontece**: todas as 5 threads retornavam `SUCCEEDED`, cada uma com um
`newBalance` diferente e crescente, e o saldo final da conta ficava com **5x**
o valor do crédito em vez de 1x. Nenhuma exceção era lançada.

Causa raiz: `Transaction.id` é atribuído pelo cliente (não há
`@GeneratedValue`) e a entidade não tem `@Version`. Nessas condições, o Spring
Data JPA considera qualquer entidade com id não-nulo como "não nova" e o
`save()` do repositório chama `entityManager.merge()` em vez de
`entityManager.persist()`. `merge()` não é um INSERT puro — ele faz um SELECT
pela chave e decide sozinho entre inserir ou atualizar, então duas threads
processando a mesma `transactionId` nunca colidem numa constraint: cada uma
simplesmente sobrescreve a linha da anterior, silenciosamente, e cada uma já
tinha aplicado a operação de negócio (crédito/débito) em cima do saldo que a
anterior tinha acabado de gravar.

## Decisão
Duas mudanças, feitas juntas:

1. **`Transaction implements Persistable<UUID>`**, com um campo transiente
   `isNew` que só é `true` quando o objeto é construído pelo construtor de
   negócio (`new Transaction(...)`). Entidades carregadas do banco pelo
   Hibernate usam o construtor protegido vazio e nunca passam por esse
   construtor, então continuam reportando `isNew() == false`. Isso força o
   `save()` do Spring Data a sempre chamar `persist()` para uma transação
   genuinamente nova — restaurando o INSERT real e, com ele, a constraint de
   chave primária como linha de defesa de verdade.

2. **`TransactionRecorder`** (novo componente): isola a tentativa de gravar
   uma transação nova (lock pessimista na conta + aplicar a operação + salvar
   a transação) numa transação de banco própria
   (`@Transactional(propagation = REQUIRES_NEW)`). Precisa ser uma classe
   separada porque `@Transactional` não tem efeito em chamadas internas
   (self-invocation) dentro da mesma classe — o mesmo motivo pelo qual o
   Resilience4j (ADR 0011) usa a API programática em vez de anotações.
   `TransactionService.doAuthorize` chama `transactionRecorder.recordNew(...)`
   dentro de um `try/catch`: se vier `DataIntegrityViolationException` (a
   constraint de chave primária rejeitou de verdade um `INSERT` duplicado),
   busca a transação que a outra thread já salvou e devolve ela como resultado
   idempotente — em vez de propagar um erro 500 para o cliente perdedor da
   corrida.

## Consequências
- Sob duas ou mais requisições verdadeiramente concorrentes com o mesmo
  `transactionId`: exatamente uma processa a operação de negócio; as demais
  recebem a mesma resposta de sucesso da vencedora (nunca um erro), e o saldo
  nunca é aplicado mais de uma vez.
- Coberto por teste real (não só unitário com mocks):
  `ConcurrentDebitTest.mesmoTransactionIdConcorrenteAplicaOCreditoApenasUmaVez`
  roda 10 threads reais contra Postgres via Testcontainers com a mesma
  `transactionId` e afirma `transactionRepository.count() == 1` e o saldo
  final correto.
- A lógica de aplicar a operação (antes em `TransactionService.applyOperation`)
  migrou para `TransactionRecorder`, que agora é o único responsável por
  mutar o saldo e persistir a transação. `TransactionService` ficou só com a
  orquestração: checagem de idempotência sequencial, delegação ao recorder, e
  recuperação da corrida concorrente.
- `TransactionServiceTest` (Mockito) testa a orquestração mockando
  `TransactionRecorder`; `TransactionRecorderTest` (novo) testa a lógica de
  negócio que migrou para lá, incluindo uma asserção explícita de que
  `transaction.isNew() == true` logo após a construção.

## Alternativas consideradas
- **Deixar como estava, só documentado**: rejeitada depois da investigação —
  o risco real era corrupção silenciosa de saldo sob concorrência verdadeira,
  não um erro HTTP inofensivo. Para uma API de autorização de transações
  financeiras, isso não é um gap aceitável de se deixar para depois.
- **Constraint `UNIQUE` adicional ou lock a nível de aplicação por
  `transactionId`** (ex.: `SELECT ... FOR UPDATE` numa tabela de locks, ou um
  `ConcurrentHashMap` de mutexes em memória): resolveria a serialização, mas
  ou exigiria uma tabela/infra extra, ou não funcionaria com múltiplas
  instâncias da aplicação atrás de um load balancer (lock em memória é local
  ao processo). A abordagem escolhida usa apenas a constraint de chave
  primária que já existe no schema, funcionando corretamente mesmo com N
  instâncias da aplicação rodando ao mesmo tempo.
