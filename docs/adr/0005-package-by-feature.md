# ADR 0005 — Package by feature

## Status
Aceito

## Contexto
Pacotes podem ser organizados por tipo técnico (`controllers/`, `services/`,
`entities/`, `repositories/`) ou por conceito de negócio.

## Decisão
Organização por feature/conceito de negócio dentro de cada camada:
`domain/account`, `domain/transaction`, `domain/exception`, com `api`,
`service`, `repository` e `messaging` como camadas técnicas de nível superior.
Ver a árvore completa no README.

## Consequências
- Abrir `domain/account` mostra tudo que compõe o conceito "conta"
  (`Account`, `AccountStatus`) — não é preciso pular entre `entities/`,
  `enums/` e `repositories/` para entender uma única entidade de negócio.
- "Screaming architecture": a estrutura de pastas comunica o domínio
  (conta, transação) antes de comunicar a tecnologia (JPA, REST).
- Trade-off: para quem está acostumado com "package by layer" (comum em
  tutoriais Spring), a primeira navegação exige reaprender onde procurar.

## Alternativas consideradas
- **Package by layer** (`controllers/`, `services/`, `repositories/`,
  `entities/`): mais familiar para times acostumados a esse padrão, mas escala
  mal à medida que o número de entidades cresce — cada pasta técnica vira uma
  lista plana com N arquivos não relacionados entre si.
