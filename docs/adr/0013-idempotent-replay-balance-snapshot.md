# ADR 0013 — Reenvio idempotente mostra o saldo da época da transação, não o saldo atual

## Status
Aceito

## Contexto
Achado em teste manual (não em código automatizado): um débito foi recusado
por saldo insuficiente (saldo 10.000, débito de 10.900 → `FAILED`). Depois,
a conta recebeu mais crédito (saldo foi para 20.000) e o **mesmo
`transactionId`** daquele débito recusado foi reenviado. A resposta idempotente
retornou `status: FAILED`, `reason: "Debito recusado: saldo insuficiente."`,
mas `account.balance = 20000.00` — o saldo *atual* da conta, não o saldo de
quando a recusa aconteceu. Um cliente lendo essa resposta isoladamente veria
uma contradição aparente: "recusado por falta de saldo" ao lado de um saldo
que claramente cobriria a operação.

Causa raiz: `TransactionResponse.from` montava o campo `account.balance` a
partir de `Account.getBalance()` — o saldo **ao vivo**, buscado de novo do
banco (`TransactionService.doAuthorize`, ramo de idempotência:
`accountRepository.findById(tx.getAccountId())`). No fluxo de uma transação
nova isso é inofensivo (o saldo ao vivo e o saldo recém-gravado na transação
são o mesmo valor, no mesmo instante). No reenvio idempotente, porém, a
transação (`tx`) é congelada no passado, e a conta buscada é do presente —
misturar os dois no mesmo payload é a origem da confusão.

## Decisão
`TransactionResponse.from` passou a usar `Transaction.getNewBalance()` para
montar `account.balance`, em vez de `Account.getBalance()`. `newBalance` já é
gravado em toda transação (`TransactionService.doAuthorize`) como o saldo
resultante **daquela operação especificamente** — para `FAILED`, é igual a
`previousBalance` (nada mudou); para `SUCCEEDED`, é o saldo logo após a
operação. É exatamente o dado "congelado" que faltava.

## Consequências
- Resposta de uma transação nova e resposta de um reenvio idempotente da
  mesma transação são **byte-a-byte idênticas** (exceto, claro, o log de que
  foi um reenvio) — que é o comportamento esperado de idempotência: a mesma
  requisição sempre retorna a mesma resposta.
- Nenhuma mudança de comportamento no fluxo de transação nova: `newBalance`
  já era, e continua sendo, o mesmo valor que `Account.getBalance()` tinha no
  momento em que a transação foi gravada.
- Coberto por teste dedicado (`TransactionResponseTest.reenvioIdempotenteMostraSaldoDaEpocaDaTransacaoNaoSaldoAtualDaConta`):
  monta uma `Transaction` FAILED com `newBalance = 10000` e uma `Account` cujo
  saldo ao vivo é 20000 (simulando créditos posteriores), e verifica que a
  resposta mostra 10000.

## Alternativas consideradas
- **Manter o saldo ao vivo, mas sinalizar no payload que é um reenvio**
  (ex.: campo `replayed: true`): resolveria a confusão de outra forma, mas
  não era isso que o enunciado pedia no contrato de resposta, e adicionaria um
  campo novo só para compensar uma inconsistência que tem uma correção mais
  direta.
- **Bloquear reenvio depois que o saldo mudar**: sem sentido — a idempotência
  existe justamente para não reprocessar, independente do que aconteceu depois
  com a conta.
