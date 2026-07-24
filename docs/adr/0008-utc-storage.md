# ADR 0008 — UTC no banco, fuso horário só na borda

## Status
Aceito

## Contexto
O enunciado exige timestamp ISO8601 na resposta, com exemplo em
`-03:00` (horário de Brasília). O sistema pode, no futuro, atender clientes em
outros fusos, e um servidor de produção normalmente roda em UTC.

## Decisão
`hibernate.jdbc.time_zone: UTC` — todo timestamp é persistido em UTC
(`TIMESTAMPTZ` no Postgres). A conversão para `America/Sao_Paulo` acontece só
no DTO de resposta (`TransactionResponse.from`, usando
`tx.getCreatedAt().atZoneSameInstant(BRAZIL)`).

## Consequências
- Nenhuma ambiguidade de fuso horário dentro do domínio ou do banco — todo
  cálculo/comparação de datas internamente usa um único referencial (UTC).
- Se a API precisar atender outro fuso no futuro, a mudança fica isolada na
  camada de apresentação (DTO), sem tocar em domínio, persistência ou lógica
  de negócio.

## Alternativas consideradas
- **Gravar já em horário de Brasília**: mais simples à primeira vista, mas
  amarra o domínio a um fuso específico e complica horário de verão/mudanças
  de regra de fuso e expansão para outros mercados.
