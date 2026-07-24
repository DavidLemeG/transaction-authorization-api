# ADR 0007 — BigDecimal para valores monetários

## Status
Aceito

## Contexto
Saldo e valores de transação são dinheiro. Erros de arredondamento binário
(`double`/`float`) são inaceitáveis em um sistema bancário.

## Decisão
`BigDecimal` em todo o domínio (`Account.balance`, `Transaction.amount`,
`previousBalance`, `newBalance`), mapeado para `NUMERIC(19,2)` no Postgres
(ver `V1__init.sql`). Validação de entrada usa `@Digits(integer = 17, fraction = 2)`
em `TransactionRequest.Amount.value` para rejeitar valores com mais de 2 casas
decimais.

## Consequências
- `AccountTest.precisaoDecimalSomaZeroPontoUmMaisZeroPontoDoisIgualAZeroPontoTres`
  prova que `0.1 + 0.2 == 0.3` exatamente — o teste clássico que `double`
  reprova (`0.1 + 0.2 == 0.30000000000000004` em ponto flutuante binário).
- Todas as comparações de saldo usam `compareTo` (nunca `equals`), já que
  `BigDecimal.equals` também compara escala (`new BigDecimal("10.0").equals(new BigDecimal("10.00"))`
  é `false`), o que quebraria comparações de saldo em `canDebit`/testes.

## Alternativas consideradas
- **`double`/`float`**: descartado sem ressalvas — representação binária de
  frações decimais é imprecisa, inadequado para dinheiro.
- **Inteiro representando centavos** (`long amountInCents`): evita todo o
  cuidado com escala do `BigDecimal`, mas exige conversão manual em cada
  entrada/saída (JSON usa `97.07`, não `9707`) e não é o padrão do ecossistema
  JPA/Postgres para `NUMERIC`. `BigDecimal` mapeia diretamente para o tipo de
  coluna e para o formato JSON do enunciado.
