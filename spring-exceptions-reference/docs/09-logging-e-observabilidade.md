# 09 — Logging e observabilidade de erros

[← Integrações](08-integracoes-transacoes-e-async.md) · [Índice](README.md) · [Próximo: Testando erros →](10-testando-erros.md)

---

## 9.1 Logue uma vez, no lugar certo

```java
// ❌ log-and-rethrow em cada camada: o mesmo erro, 3 stack traces, 3 alertas
// repository
catch (SQLException e) { log.error("erro no repo", e); throw new RepoException(e); }
// service
catch (RepoException e) { log.error("erro no service", e); throw e; }
// controller
catch (Exception e) { log.error("erro no controller", e); return 500; }
```

**Regra:** quem **trata** a exceção (decide o desfecho) é quem loga. Numa API, esse lugar é o
`GlobalExceptionHandler`. Quem só propaga ou traduz **não loga**.

Exceção: se você **trata e segue** (fallback, valor default), logue ali, porque a exceção não vai
chegar a lugar nenhum.

## 9.2 Nível e stack trace por categoria

Política do projeto (`GlobalExceptionHandler.logException`):

| Situação | Nível | Stack trace? | Por quê |
|---|---|---|---|
| Validação / protocolo (400, 404 de rota, 405, 415) | `DEBUG` | Não | Ruído; o cliente errou e já recebeu a lista |
| Erro de negócio esperado (404, 409, 422) | `INFO` | Não | Útil para auditoria e suporte, mas não é problema nosso |
| Falha de integração (502, 503, 504) | `WARN` | **Sim** | A causa (SDK, timeout) está na cadeia; o alerta vem da métrica |
| Erro inesperado (500) | `ERROR` | **Sim** | Bug. Cada ocorrência merece investigação |

> Um log `ERROR` deveria significar "alguém precisa olhar". Se 404 de cliente é `ERROR`, a equipe
> aprende a ignorar `ERROR`, e o bug real passa despercebido.

## 9.3 Correlation ID: ligando a resposta ao log

Quando um cliente reporta "deu erro", a primeira pergunta é "qual requisição?". O
[`CorrelationIdFilter`](../src/main/java/dev/cheatsheet/exceptions/shared/web/CorrelationIdFilter.java):

1. Lê `X-Correlation-Id` da requisição (se for **válido**; senão gera um UUID).
2. Coloca no **MDC**, então toda linha de log da requisição o inclui
   (`logging.pattern.level: "%5p [%X{correlationId:-}]"`).
3. Devolve no header de resposta **e** no corpo do erro.

```
ERROR [53a3aad3-6316-4389-9b70-c81f175a18b0] ... GlobalExceptionHandler : Erro inesperado [500 INTERNAL_ERROR]
java.lang.IllegalStateException: SELECT * FROM customers ...
```

O cliente vê só `"correlationId": "53a3aad3-..."`; o suporte busca esse id no log e acha a stack trace.

**Valide o header de entrada.** Um id com quebra de linha permitiria **log injection** (forjar
linhas de log). O filtro aceita só `[A-Za-z0-9._-]{1,64}` (teste `correlationIdMaliciosoEhDescartado`).

> **Em produção**, prefira **Micrometer Tracing** (OpenTelemetry): o Spring Boot propaga o
> `traceparent` (W3C) entre serviços e coloca `traceId`/`spanId` no MDC automaticamente
> (`logging.pattern.correlation`). Aí você expõe o `traceId` no Problem Detail no lugar do id próprio.
> O filtro do projeto existe para deixar o mecanismo explícito.

## 9.4 Métricas: alerta vem de número, não de log

O Spring Boot (Actuator) já publica `http.server.requests` com as tags `status`, `outcome`, `uri` e
`exception`. Para erros tratados por `@ExceptionHandler`, informe a exceção à observação para que a
tag `exception` não fique como `none`:

```java
ServerHttpObservationFilter.findObservationContext(request)
        .ifPresent(context -> context.setError(ex));
```

E publique um contador pelo **code**, que é muito mais acionável que o status:

```java
meterRegistry.counter("app.errors", "code", code.code(), "category", code.category().name()).increment();
```

Alertas sugeridos:

| Alerta | Sinal |
|---|---|
| Taxa de 5xx acima do SLO | `http.server.requests{outcome=SERVER_ERROR}` / total |
| Dependência degradando | `app.errors{category=DEPENDENCY_TIMEOUT}` subindo |
| Possível bug de cliente/release | pico súbito de um code 4xx específico após deploy |
| Qualquer `INTERNAL_ERROR` novo | `app.errors{code=INTERNAL_ERROR}` > 0 após deploy |

Use **o code como tag, nunca o `detail`** (cardinalidade: o detail tem ids e valores).

## 9.5 O que nunca vai para o log

- Senhas, tokens, chaves, números de cartão, documentos.
- Corpo inteiro da requisição/resposta "para debug".
- Mensagens de exceção de terceiros que **ecoam o input** (alguns drivers incluem o valor que
  violou a constraint).

Mascare na origem. Lembre que `e.getMessage()` de uma `ConstraintViolationException` do banco pode
conter o valor duplicado (um e-mail, por exemplo).

---

### ✅ Resumo do capítulo

- Logue uma vez, onde a exceção é tratada.
- Nível e stack trace pela categoria: 4xx sem stack; 5xx com stack.
- Correlation/trace id no MDC, no header e no corpo. Valide o que vem de fora.
- Alertas por métrica (status e **code**), não por linha de log.
