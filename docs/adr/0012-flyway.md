# ADR 0012 — Flyway para versionamento de schema

## Status
Aceito

## Contexto
O schema (`accounts`, `transactions`) precisa evoluir de forma auditável e
reproduzível entre ambientes (dev local, CI, produção), sem depender do
Hibernate para criar/alterar tabelas automaticamente em produção.

## Decisão
Flyway com `hibernate.ddl-auto: validate` — o Hibernate nunca cria ou altera
tabelas, apenas valida que as entidades JPA batem com o schema já migrado.
Migrations em `src/main/resources/db/migration`:
`V1__init.sql` (tabelas `accounts`/`transactions`) e
`V2__add_reason_to_transactions.sql` (coluna `reason`, adicionada quando o
motivo da recusa passou a ser gravado e exposto na resposta).

## Consequências
- Todo `ALTER TABLE` fica versionado e revisável em code review, com histórico
  em `flyway_schema_history`.
- `ddl-auto: validate` funciona como um segundo teste de sanidade: se o mapeamento
  JPA de uma entidade divergir do schema migrado, a aplicação falha ao subir
  (fail-fast) em vez de silenciosamente aceitar a divergência.
- Custo: toda mudança de schema exige escrever a migration manualmente — não
  há "auto-alteração" de conveniência em ambiente de desenvolvimento.

## Alternativas consideradas
- **`ddl-auto: update`**: conveniente em prototipagem, mas inaceitável em um
  sistema de missão crítica — o Hibernate pode inferir alterações destrutivas
  ou incorretas sem revisão humana.
- **Liquibase**: alternativa equivalente ao Flyway; Flyway foi escolhido por
  usar SQL puro nas migrations (mais direto para quem revisa o schema) em vez
  do changelog XML/YAML do Liquibase.
