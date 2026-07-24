# ADR 0001 — Java 17 + Spring Boot 3.3.5

## Status
Aceito

## Contexto
A vaga é para squads de core banking do Itaú. Precisávamos de uma stack madura,
com suporte de longo prazo e compatível com as bibliotecas exigidas pelo
desafio (AWS SDK v2, Resilience4j, SpringDoc/OpenAPI).

## Decisão
Java 17 (LTS) com Spring Boot 3.3.5.

## Consequências
- Acesso a `record` (usado em todos os DTOs e comandos do serviço, ex.:
  `TransactionRequest`, `AuthorizationCommand`), pattern matching e demais
  melhorias de linguagem do Java 17.
- Spring Boot 3.x exige Jakarta EE (namespace `jakarta.*`), o que já reflete o
  estado atual do ecossistema Spring.
- Compatibilidade direta com `resilience4j-spring-boot3`,
  `springdoc-openapi-starter-webmvc-ui` 2.x e AWS SDK v2 — todos usados no projeto.

## Alternativas consideradas
- **Spring Boot 4.1.0**: versão inicialmente usada, mas trocada por 3.3.5 por
  ser uma linha mais madura e mais próxima do que costuma estar em produção em
  ambientes financeiros no momento da entrega deste desafio.
- **Java 21**: também LTS e viável; Java 17 foi mantido por ser o mínimo exigido
  pelo Spring Boot 3.x e reduzir risco de incompatibilidade com alguma
  dependência transitiva.
