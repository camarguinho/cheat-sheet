# 06 — Problem Details (RFC 9457): o formato da resposta de erro

[← Spring MVC](05-tratamento-de-erros-no-spring-mvc.md) · [Índice](README.md) · [Próximo: Validação →](07-validacao.md)

---

## 6.1 Por que um padrão

Sem padrão, cada time inventa o seu: `{"error": ...}`, `{"message": ...}`, `{"errors": [...]}`,
`{"success": false}`. O cliente que integra com cinco serviços escreve cinco parsers.

A **RFC 9457** (*Problem Details for HTTP APIs*, 2023, substitui a RFC 7807) define um formato
JSON padrão e um media type próprio: **`application/problem+json`**. O Spring suporta nativamente
desde a versão 6, com a classe `ProblemDetail`.

## 6.2 Os campos padrão

| Campo | Tipo | Significado |
|---|---|---|
| `type` | URI | Identifica o **tipo** de problema. Deve ser estável; idealmente aponta para documentação. Padrão: `about:blank`. |
| `title` | string | Resumo curto, **igual para todas as ocorrências** do mesmo `type`. |
| `status` | int | O status HTTP (repetido no corpo para quem só tem o corpo, como um log). |
| `detail` | string | Explicação **desta ocorrência**. |
| `instance` | URI | Identifica esta ocorrência (no Spring, o path da requisição por padrão). |

Além desses, qualquer outro campo é um **membro de extensão** (*extension member*). É aí que entram o
seu `code`, `correlationId`, `errors` etc.

`type: about:blank` significa "este problema não tem semântica além do status": é o que o Spring
usa para erros de protocolo (405, 415...).

## 6.3 O contrato deste projeto

| Campo | Sempre? | Origem |
|---|---|---|
| `type` | sim | `/errors/{code}` para erros da aplicação; `about:blank` para protocolo |
| `title` | sim | `ErrorCode.title()` ou o padrão do Spring |
| `status` | sim | `HttpStatusMapper` |
| `detail` | sim | `ErrorCode.format(args)`; genérico para 500 |
| `instance` | sim | path da requisição |
| `code` | **sim** | `ErrorCode.code()` ou `ProtocolErrorCodes` |
| `correlationId` | **sim** | `CorrelationIdFilter` (também no header `X-Correlation-Id`) |
| `timestamp` | sim | instante do erro, ISO-8601 UTC |
| `errors` | em validação | lista de `{field, message}` |
| `retryable` | em integração | `true` se repetir pode funcionar |
| *detalhes de domínio* | quando houver | `ApplicationException.getDetails()` (ex.: `limit`, `total`) |

## 6.4 Exemplos reais (capturados da aplicação)

**404 — recurso não encontrado**

```http
HTTP/1.1 404
Content-Type: application/problem+json
X-Correlation-Id: 0d290d82-2147-44c6-9c31-f39e268984c1

{
  "type": "/errors/ORDER_NOT_FOUND",
  "title": "Pedido não encontrado",
  "status": 404,
  "detail": "O pedido 3f2b8c1e-0000-4000-8000-000000000000 não foi encontrado.",
  "instance": "/orders/3f2b8c1e-0000-4000-8000-000000000000",
  "code": "ORDER_NOT_FOUND",
  "timestamp": "2026-11-09T15:11:08.129060932Z",
  "correlationId": "0d290d82-2147-44c6-9c31-f39e268984c1"
}
```

**400 — validação**

```json
{
  "type": "/errors/VALIDATION_FAILED",
  "title": "Requisição inválida",
  "status": 400,
  "detail": "Um ou mais campos são inválidos. Veja a lista em 'errors'.",
  "instance": "/orders",
  "code": "VALIDATION_FAILED",
  "correlationId": "3a2905d8-ae52-46ca-96c3-84548978c131",
  "timestamp": "...",
  "errors": [
    { "field": "customerId",         "message": "must not be blank" },
    { "field": "items[0].quantity",  "message": "must be greater than 0" },
    { "field": "items[0].unitPrice", "message": "must be greater than or equal to 0.01" }
  ]
}
```

**422 — regra de negócio com dados estruturados**

```json
{
  "type": "/errors/ORDER_TOTAL_LIMIT_EXCEEDED",
  "title": "Limite do pedido excedido",
  "status": 422,
  "detail": "O total do pedido (15000.00) excede o limite permitido de 10000.00.",
  "code": "ORDER_TOTAL_LIMIT_EXCEEDED",
  "limit": 10000.00,
  "total": 15000.00,
  "...": "..."
}
```

**503 — dependência indisponível**

```http
HTTP/1.1 503
Retry-After: 30
Content-Type: application/problem+json

{"type":"/errors/PAYMENT_PROVIDER_UNAVAILABLE","title":"Provedor de pagamento indisponível",
 "status":503,"code":"PAYMENT_PROVIDER_UNAVAILABLE","retryable":true, ...}
```

**405 — erro de protocolo (Spring), enriquecido com o mesmo contrato**

```json
{
  "title": "Method Not Allowed",
  "status": 405,
  "detail": "Method 'DELETE' is not supported.",
  "instance": "/orders/0c13c260-...",
  "code": "METHOD_NOT_ALLOWED",
  "correlationId": "3b907cb4-...",
  "timestamp": "..."
}
```

**500 — bug: nada vaza, só o `correlationId`**

```json
{
  "type": "/errors/INTERNAL_ERROR",
  "title": "Erro interno",
  "status": 500,
  "detail": "Ocorreu um erro inesperado. Informe o correlationId ao suporte.",
  "code": "INTERNAL_ERROR",
  "correlationId": "53a3aad3-6316-4389-9b70-c81f175a18b0"
}
```

## 6.5 `ProblemDetail` no Spring

```java
ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "O pedido X não foi encontrado.");
problem.setType(URI.create("/errors/ORDER_NOT_FOUND"));
problem.setTitle("Pedido não encontrado");
problem.setProperty("code", "ORDER_NOT_FOUND");      // membro de extensão
return ResponseEntity.status(404).body(problem);
```

- Retornando `ProblemDetail` (ou `ResponseEntity<ProblemDetail>`), o Spring escolhe
  `application/problem+json` automaticamente.
- As propriedades de extensão são serializadas **no nível raiz** do JSON (o Spring registra um mixin
  Jackson para isso), não dentro de um objeto `properties`.
- Se `instance` estiver vazio, o Spring preenche com o path da requisição.

## 6.6 Regras de design do contrato

1. **`type` (ou `code`) é o que o cliente usa para decidir.** O projeto mantém os dois em sincronia
   (`type = base + code`); `code` é mais confortável de usar em `switch`.
2. **`title` é constante por tipo**; o que varia vai em `detail`.
3. **Use URI absoluta e estável em produção** para o `type` (`app.errors.type-base-uri:
   https://api.suaempresa.com/errors/`). Ela não precisa estar online para funcionar como
   identificador, mas é bom que esteja: aqui, `GET /errors/{code}` documenta o erro.
4. **Nomes de extensão também são contrato.** Renomear `retryable` é breaking change.
5. **Nunca** coloque stack trace, nome de classe, SQL ou mensagem de exceção de terceiro.
6. **Um formato para todos os erros**, inclusive 404 de rota e 405. Não deixe "buracos" com o formato padrão do Boot.

## 6.7 O lado de quem consome (RestClient)

```java
try {
    return restClient.get().uri("/orders/{id}", id).retrieve().body(OrderResponse.class);
} catch (HttpClientErrorException e) {                          // 4xx
    ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
    String code = problem != null && problem.getProperties() != null
            ? (String) problem.getProperties().get("code") : null;
    if ("ORDER_NOT_FOUND".equals(code)) {
        return null;                                            // tratamento específico
    }
    throw new IntegrationException(MY_CODE, e);                 // fallback por família
} catch (HttpServerErrorException | ResourceAccessException e) { // 5xx ou rede/timeout
    throw new IntegrationException(DEPENDENCY_DOWN, e);
}
```

Ou centralizando no builder: `.defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {...})`.
Ver [capítulo 08](08-integracoes-transacoes-e-async.md).

---

### ✅ Resumo do capítulo

- Use RFC 9457 (`application/problem+json`) e `ProblemDetail` do Spring.
- Padrão: `type`, `title`, `status`, `detail`, `instance`. Extensões: `code`, `correlationId`, `errors`...
- Um formato único para todos os erros, inclusive os do framework.
- O cliente decide por `code`/`type`, com fallback por status.
