# ADR 0003 — Rich Domain Model

## Status
Aceito

## Contexto
A regra "débito que deixaria o saldo negativo deve ser recusado, sem alterar o
saldo" é a regra central do desafio. Ela pode morar em duas camadas: no
service (modelo anêmico, `Account` como bag de getters/setters) ou na própria
entidade `Account`.

## Decisão
`Account` é um rich domain model: `credit(amount)`, `canDebit(amount)` e
`debit(amount)` vivem na entidade (`domain/account/Account.java`), não há
`setBalance` público, e `debit()` lança `InsufficientFundsException` se
`canDebit()` for falso. O construtor força saldo inicial zero e moeda BRL —
não é possível instanciar uma `Account` em estado inválido.

## Consequências
- A invariante "saldo nunca negativo" só pode ser violada se alguém adicionar
  um novo método público que mexa em `balance` diretamente — a regra não pode
  ser "esquecida" em um novo caso de uso do service, porque o service não tem
  acesso direto ao campo.
- `TransactionService` fica fino: chama `account.credit(...)` ou
  `account.canDebit(...)`/`account.debit(...)`, sem reimplementar a regra.
- Testável isoladamente sem Spring nem mocks (`AccountTest`), o que resultou em
  9 testes cobrindo 100% da classe.

## Alternativas consideradas
- **Modelo anêmico** (regra no `TransactionService`, `Account` só com getters/setters):
  mais simples de escrever inicialmente, mas a regra de saldo ficaria
  espalhada por qualquer service que precisasse alterar saldo no futuro
  (ex.: um endpoint de estorno), com risco de duplicação ou inconsistência.
