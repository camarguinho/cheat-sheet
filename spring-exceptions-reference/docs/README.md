# 📘 Exceções em Java e Spring — o guia do engenheiro

Um ebook curto e prático sobre **como falhar bem**: exceções em Java, error codes, HTTP status e o
tratamento centralizado no Spring. Cada capítulo aponta para o código real do
[projeto de referência](../README.md), que compila, roda e tem testes para cada afirmação importante.

## Para quem é

Para quem já escreve Spring no dia a dia e quer sair do "coloca um try/catch e devolve 500" para um
modelo de erro **consistente, seguro e operável**, e conseguir defender cada decisão numa revisão de design.

## O modelo em uma figura

```
 DOMÍNIO (sem HTTP)                BORDA WEB                            CLIENTE
 ──────────────────                ─────────                            ───────
 throw new NotFoundException(      GlobalExceptionHandler               HTTP 404
   ORDER_NOT_FOUND, id)      ──►     categoria NOT_FOUND          ──►   application/problem+json
                                     HttpStatusMapper → 404             {
 ErrorCode                           ProblemDetail                        "code": "ORDER_NOT_FOUND",
   └─► ErrorCategory                 + code + correlationId               "correlationId": "…",
                                     log 1x, nível pela categoria         ...
                                                                        }
```

## Sumário

| # | Capítulo | Você vai aprender |
|---|---|---|
| 01 | [Fundamentos de exceções em Java](01-fundamentos-de-excecoes-em-java.md) | Hierarquia, checked vs unchecked, causa, try-with-resources, threads |
| 02 | [Desenhando as exceções da aplicação](02-desenhando-excecoes-da-aplicacao.md) | Esperado vs inesperado, hierarquia, tradução na fronteira, anti-patterns |
| 03 | [Error codes](03-error-codes.md) | Por que existem, formato, catálogo, evolução, i18n |
| 04 | [HTTP status codes](04-http-status-codes.md) | Árvore de decisão, 400 vs 422, 5xx, retry |
| 05 | [Tratamento de erros no Spring MVC](05-tratamento-de-erros-no-spring-mvc.md) | Resolvers, `@RestControllerAdvice`, `ResponseEntityExceptionHandler`, filters, `/error` |
| 06 | [Problem Details (RFC 9457)](06-problem-details-rfc-9457.md) | O formato padrão da resposta de erro e o contrato do projeto |
| 07 | [Validação](07-validacao.md) | Borda, domínio e caso de uso; as exceções de validação do Spring |
| 08 | [Integrações, transações e async](08-integracoes-transacoes-e-async.md) | RestClient, retry, rollback, `DataAccessException`, `@Async`, filas |
| 09 | [Logging e observabilidade](09-logging-e-observabilidade.md) | Logar uma vez, níveis, correlation id, métricas |
| 10 | [Testando erros](10-testando-erros.md) | Testes de contrato, testes negativos, MockMvc |
| 11 | [Checklist e anti-patterns](11-checklist-e-anti-patterns.md) | Checklist de review e mapa conceito → código |

## Respostas rápidas

**Exception handler?** Um único `@RestControllerAdvice` estendendo `ResponseEntityExceptionHandler`,
com três famílias de tratamento: erros da aplicação, erros do framework e a rede de segurança para
bugs. Controllers e services sem try/catch. → [cap. 05](05-tratamento-de-erros-no-spring-mvc.md)

**Error code?** Identificador estável e semântico (`ORDER_NOT_FOUND`), um enum por contexto,
catálogo central validado no startup. É o que o cliente usa para decidir; nunca muda de significado.
→ [cap. 03](03-error-codes.md)

**HTTP status?** Derivado da **categoria** do erro, num único mapper na borda. 4xx = cliente, 5xx =
servidor; 400 forma, 422 negócio, 409 estado; 502/503/504 para dependências.
→ [cap. 04](04-http-status-codes.md)

**Formato?** RFC 9457 Problem Details (`application/problem+json`), com `code` e `correlationId`
em todo erro. → [cap. 06](06-problem-details-rfc-9457.md)

## Ordem de leitura sugerida

- **Com pressa:** este README → [04](04-http-status-codes.md) → [05](05-tratamento-de-erros-no-spring-mvc.md) → [11](11-checklist-e-anti-patterns.md).
- **Completo:** em ordem. Os capítulos 01–04 não dependem de Spring.
