# ADR 0009 — HTTP 200 para transação recusada por saldo insuficiente

## Status
Aceito

## Contexto
Um débito que deixaria o saldo negativo deve ser **recusado**, não é um erro
de requisição. O cliente precisa de uma forma de distinguir "sua requisição
está malformada" de "sua requisição foi processada, mas a decisão de negócio
foi recusar".

## Decisão
Débito recusado por saldo insuficiente retorna **HTTP 200**, com
`transaction.status = "FAILED"` e `transaction.reason` preenchido no corpo
(`TransactionService.applyOperation` verifica `canDebit` e retorna um
`OperationResult` com status `FAILED` sem lançar exceção). Já `4xx` é reservado
para requisição malformada (payload inválido, conta inexistente).

## Consequências
- O contrato de resposta do enunciado (`status: SUCCEEDED/FAILED` dentro do
  corpo) é respeitado literalmente: o HTTP status reflete "a requisição foi
  processada", o campo `status` do corpo reflete o resultado do negócio.
- **Nota de implementação (corrigida durante a revisão de testes):**
  `GlobalExceptionHandler` ainda possui um `@ExceptionHandler(InsufficientFundsException.class)`
  retornando HTTP 422. Hoje esse handler nunca é acionado no fluxo normal —
  `TransactionService` verifica `canDebit()` antes de chamar `debit()`, então a
  exceção nunca chega ao controller. Ele foi mantido como salvaguarda
  defensiva (caso `debit()` seja chamado em outro contexto no futuro sem passar
  por `canDebit()` antes), mas é código não exercitado pelo caminho feliz —
  documentado aqui para não ser confundido com o comportamento real da API.

## Alternativas consideradas
- **HTTP 422 (Unprocessable Entity) para débito recusado**: é o padrão REST
  "tecnicamente correto" para uma regra de negócio violada, mas obrigaria todo
  cliente a tratar debitos recusados como erro HTTP em vez de como um dos dois
  resultados esperados de uma autorização — pior ergonomia para o caso de uso
  descrito no enunciado, que já modela "aprovado"/"recusado" como um resultado
  válido dentro do próprio contrato de resposta.
