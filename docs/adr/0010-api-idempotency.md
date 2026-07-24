# ADR 0010 — Idempotência via transactionId

## Status
Aceito

## Contexto
`transactionId` é fornecido pelo cliente na URL (`POST /transactions/{transactionId}`).
Um retry de rede (timeout no cliente, mas a requisição foi processada no
servidor) reenviaria o mesmo `transactionId`. Sem tratamento, isso poderia
gerar um erro 500 (chave primária duplicada) ou, pior, debitar/creditar a
conta duas vezes se a checagem de duplicidade não existisse.

## Decisão
`TransactionService.authorize` verifica `transactionRepository.findById(transactionId)`
antes de processar. Se a transação já existe, retorna a resposta já persistida
sem tocar no saldo da conta novamente.

## Consequências
- Reenviar a mesma requisição (mesmo `transactionId`) é seguro — não há
  double debit/credit. Coberto por teste
  (`TransactionServiceTest.reenvioDoMesmoTransactionIdRetornaTransacaoExistenteSemReprocessar`).
- **Gap conhecido, documentado no código** (`TransactionService.java`,
  comentário ao final da classe): duas requisições **verdadeiramente
  simultâneas** com o mesmo `transactionId` ainda podem colidir — a segunda
  thread pode passar pelo `findById` antes da primeira commitar, e then falhar
  ao tentar `save()` com `DataIntegrityViolationException` (PK duplicada) em
  vez de retornar a resposta idempotente. O caso comum (retry sequencial após
  timeout) está coberto; o caso de corrida verdadeira exigiria capturar essa
  exceção e buscar a transação salva pela outra thread, com
  `@Transactional(REQUIRES_NEW)` para não herdar rollback-only da transação
  que falhou.

## Alternativas consideradas
- **Chave de idempotência separada** (header `Idempotency-Key`, como Stripe):
  mais flexível (permite reenviar sem repetir o mesmo `transactionId`
  semântico), mas o enunciado já define `transactionId` como identificador
  da transação na URL — reaproveitá-lo como chave de idempotência evita
  introduzir um mecanismo paralelo não pedido pelo desafio.
