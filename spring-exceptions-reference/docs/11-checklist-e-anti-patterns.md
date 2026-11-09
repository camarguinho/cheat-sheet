# 11 — Checklist, anti-patterns e mapa do projeto

[← Testando erros](10-testando-erros.md) · [Índice](README.md)

---

## 11.1 Checklist de code review

**Exceções**
- [ ] Exceções da aplicação são unchecked e estendem uma base comum.
- [ ] Toda exceção traduzida preserva a causa (`new X(..., cause)`).
- [ ] Nenhum `catch` vazio; nenhum `catch (Throwable)`.
- [ ] Nenhum log-and-rethrow.
- [ ] `InterruptedException` restaura a flag.
- [ ] Recursos com try-with-resources; nada de `return` em `finally`.
- [ ] Exceções de libs de terceiros não atravessam a interface do adaptador.
- [ ] Validação local antes de efeitos externos (atomicidade de falha).

**Error codes**
- [ ] Todo erro esperado tem um code estável, no enum do seu contexto.
- [ ] O enum novo foi registrado no catálogo.
- [ ] Nenhum code foi renomeado ou reaproveitado.
- [ ] Mensagens e `details` são seguros para o cliente.

**HTTP**
- [ ] Status coerente: 4xx = cliente, 5xx = servidor.
- [ ] 400 para forma, 422 para negócio, 409 para estado.
- [ ] Status de dependência não é repassado (400 do provedor → 502 nosso).
- [ ] `Retry-After` em 429/503 quando possível.
- [ ] Lista vazia é 200, não 404.

**Spring**
- [ ] Controllers e services sem try/catch de "formatação de erro".
- [ ] Um `@RestControllerAdvice` estendendo `ResponseEntityExceptionHandler`.
- [ ] `handleExceptionInternal` sobrescrito: erros do framework seguem o mesmo contrato.
- [ ] Handlers específicos para `AccessDeniedException`/`AuthenticationException` se houver method security.
- [ ] `server.error.include-*` configurado para não vazar.
- [ ] `@Transactional` com checked exception tem `rollbackFor`.

**Resposta**
- [ ] `application/problem+json` em **todos** os erros (inclusive 404 de rota e 405).
- [ ] `code` e `correlationId` sempre presentes.
- [ ] 500 nunca expõe mensagem, classe ou stack.

**Operação**
- [ ] 4xx sem stack trace; 5xx com stack trace; um log por erro.
- [ ] Correlation/trace id no MDC, header e corpo.
- [ ] Métrica por code; alertas por taxa de 5xx.

**Testes**
- [ ] Cada code tem pelo menos um teste de contrato HTTP.
- [ ] Testes negativos garantem que nada vaza.

## 11.2 Os 15 anti-patterns mais comuns

| # | Anti-pattern | Por que é ruim | Faça assim |
|---|---|---|---|
| 1 | `catch (Exception e) {}` | O erro some | Trate de verdade ou deixe subir |
| 2 | Log-and-rethrow | Logs duplicados, alertas falsos | Logue só onde trata |
| 3 | `throw new RuntimeException("erro")` | Sem significado, sem code | Exceção da aplicação + code |
| 4 | Perder a causa | Stack trace inútil | `new X(msg, cause)` |
| 5 | `e.getMessage()` na resposta | Vaza SQL, classes, PII | Mensagem do code; genérica no 500 |
| 6 | `200 OK` com `success: false` | Quebra cache, retry, métricas | Status correto |
| 7 | 500 para tudo | Alerta dispara por erro do cliente | Mapeamento por categoria |
| 8 | 400 para bug | Bug fica invisível | Inesperado = 500 |
| 9 | Repassar status da dependência | 400 do provedor vira "culpa" do seu cliente | Traduzir (502) |
| 10 | HTTP no domínio | Acopla, não serve para fila/batch | Categoria no domínio, status na borda |
| 11 | Exceção como `goto` | Lento e ilegível | `Optional`, `if`, retorno |
| 12 | Cliente parseando `detail` | Quebra ao mudar o texto | Cliente decide por `code` |
| 13 | Checked em `@Transactional` sem `rollbackFor` | Commit parcial silencioso | Unchecked, ou `rollbackFor` |
| 14 | Retry em tudo | Amplifica incidentes, duplica efeitos | Só transitório + idempotente |
| 15 | Formato diferente para erros do framework | Cliente precisa de dois parsers | `handleExceptionInternal` |

## 11.3 Mapa: conceito → onde está no código

| Conceito | Arquivo |
|---|---|
| Categoria de erro (independente de protocolo) | [`shared/error/ErrorCategory.java`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ErrorCategory.java) |
| Contrato de error code | [`shared/error/ErrorCode.java`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ErrorCode.java) |
| Base das exceções esperadas | [`shared/error/ApplicationException.java`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ApplicationException.java) |
| Subclasses por categoria | [`NotFoundException`](../src/main/java/dev/cheatsheet/exceptions/shared/error/NotFoundException.java), [`ConflictException`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ConflictException.java), [`BusinessRuleException`](../src/main/java/dev/cheatsheet/exceptions/shared/error/BusinessRuleException.java), [`IntegrationException`](../src/main/java/dev/cheatsheet/exceptions/shared/error/IntegrationException.java) |
| Codes por contexto | [`OrderErrorCode`](../src/main/java/dev/cheatsheet/exceptions/order/OrderErrorCode.java), [`PaymentErrorCode`](../src/main/java/dev/cheatsheet/exceptions/payment/PaymentErrorCode.java), [`CommonErrorCode`](../src/main/java/dev/cheatsheet/exceptions/shared/error/CommonErrorCode.java) |
| Catálogo validado + endpoint `/errors` | [`catalog/`](../src/main/java/dev/cheatsheet/exceptions/catalog/) |
| Categoria → HTTP status | [`shared/web/HttpStatusMapper.java`](../src/main/java/dev/cheatsheet/exceptions/shared/web/HttpStatusMapper.java) |
| Handler global (Problem Details) | [`shared/web/GlobalExceptionHandler.java`](../src/main/java/dev/cheatsheet/exceptions/shared/web/GlobalExceptionHandler.java) |
| Erros fora do DispatcherServlet | [`shared/web/ProblemErrorAttributes.java`](../src/main/java/dev/cheatsheet/exceptions/shared/web/ProblemErrorAttributes.java) |
| Correlation ID | [`shared/web/CorrelationIdFilter.java`](../src/main/java/dev/cheatsheet/exceptions/shared/web/CorrelationIdFilter.java) |
| Tradução de exceções de terceiros | [`payment/SdkPaymentGateway.java`](../src/main/java/dev/cheatsheet/exceptions/payment/SdkPaymentGateway.java) |
| Invariantes de domínio | [`order/Order.java`](../src/main/java/dev/cheatsheet/exceptions/order/Order.java), [`order/OrderItem.java`](../src/main/java/dev/cheatsheet/exceptions/order/OrderItem.java) |
| Service sem try/catch | [`order/OrderService.java`](../src/main/java/dev/cheatsheet/exceptions/order/OrderService.java) |
| Validação na borda | [`order/OrderDtos.java`](../src/main/java/dev/cheatsheet/exceptions/order/OrderDtos.java) |

## 11.4 Leituras

- RFC 9457 — *Problem Details for HTTP APIs*
- RFC 9110 — *HTTP Semantics* (seção 15: status codes)
- Joshua Bloch — *Effective Java*, 3ª ed., capítulo 10 (itens 69–77)
- Documentação do Spring Framework — *Web MVC › Exceptions* e *Error Responses*
- Documentação do Spring Boot — *Servlet Web Applications › Error Handling*
- Techpills deste repositório: [02 — Tratamento de Erros](../../techpills/02-tratamento-de-erros.md),
  [11 — Transações](../../techpills/11-transacoes-e-consistencia.md),
  [12 — Comunicação entre Serviços](../../techpills/12-comunicacao-entre-servicos.md)
