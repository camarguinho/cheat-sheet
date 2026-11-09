# 04 — HTTP status codes

[← Error codes](03-error-codes.md) · [Índice](README.md) · [Próximo: Tratamento no Spring MVC →](05-tratamento-de-erros-no-spring-mvc.md)

---

O status é a parte do erro que **toda a infraestrutura entende**: proxies, load balancers, CDNs,
bibliotecas de retry, dashboards e alertas. Errar o status quebra mais do que o seu cliente.

## 4.1 As famílias

| Família | Significado | De quem é a culpa | Repetir a mesma requisição ajuda? |
|---|---|---|---|
| 2xx | Sucesso | — | — |
| 4xx | O cliente pediu algo que não pode ser atendido | Do **cliente** | Não, a menos que algo mude (exceto 408/429) |
| 5xx | O servidor falhou ao atender algo válido | Do **servidor** (ou de quem ele depende) | Talvez (502/503/504) |

Consequência operacional: **SLO e alertas são sobre 5xx.** Se você devolve 500 para "pedido não
encontrado", seu alerta dispara por culpa do cliente. Se devolve 400 para um bug, você nunca fica sabendo.

## 4.2 Árvore de decisão

```
A requisição chegou até a lógica de negócio?
├── Não: o framework rejeitou
│   ├── rota não existe ..................................... 404
│   ├── método não suportado na rota ........................ 405 (+ header Allow)
│   ├── Content-Type não suportado .......................... 415
│   ├── Accept impossível de atender ........................ 406
│   └── corpo ilegível / tipo errado no path/query .......... 400
├── Quem chama está identificado?
│   ├── Não ................................................. 401 (+ WWW-Authenticate)
│   └── Sim, mas sem permissão .............................. 403 (ou 404 para esconder a existência)
├── A entrada é sintaticamente válida (campos, formatos, faixas)?
│   └── Não ................................................. 400 (+ lista de campos)
├── O recurso identificado existe?
│   └── Não ................................................. 404
├── Há precondição de versão (If-Match / ETag) e ela falhou? . 412
├── Conflita com o estado atual (transição, versão, duplicata)?
│   └── Sim ................................................. 409
├── Viola regra de negócio?
│   └── Sim ................................................. 422
├── Excedeu cota/taxa? ...................................... 429 (+ Retry-After)
└── Deu errado do nosso lado
    ├── bug / inesperado .................................... 500
    ├── dependência respondeu erro/lixo ..................... 502
    ├── nós (ou dependência essencial) indisponíveis ......... 503 (+ Retry-After)
    └── dependência não respondeu a tempo ................... 504
```

## 4.3 Guia código a código

| Status | Quando usar | No projeto |
|---|---|---|
| **400 Bad Request** | Entrada malformada ou inválida que o cliente pode corrigir: JSON quebrado, campo obrigatório ausente, UUID inválido no path, valor fora de faixa. | `VALIDATION_FAILED`, `MALFORMED_REQUEST` |
| **401 Unauthorized** | Falta autenticação ou ela é inválida/expirada. O nome é histórico: significa *não autenticado*. Inclua `WWW-Authenticate`. | (Spring Security) |
| **403 Forbidden** | Autenticado, mas sem permissão. | (Spring Security) |
| **404 Not Found** | O recurso identificado não existe. Também é válido para esconder a existência de algo que o cliente não pode ver. | `ORDER_NOT_FOUND`, `ROUTE_NOT_FOUND` |
| **405 Method Not Allowed** | Rota existe, método não. O Spring preenche o header `Allow`. | `METHOD_NOT_ALLOWED` |
| **406 Not Acceptable** | O `Accept` do cliente não pode ser atendido. | `NOT_ACCEPTABLE` |
| **409 Conflict** | Pedido válido que conflita com o **estado atual**: transição de estado proibida, duplicata, versão desatualizada informada no corpo. | `ORDER_INVALID_STATE` |
| **412 Precondition Failed** | Falhou uma precondição **de cabeçalho** (`If-Match`, `If-Unmodified-Since`). Padrão para concorrência otimista via ETag. | — |
| **415 Unsupported Media Type** | `Content-Type` não suportado. | `UNSUPPORTED_MEDIA_TYPE` |
| **422 Unprocessable Content** | Requisição bem formada e válida, mas que viola uma **regra de negócio**. | `ORDER_TOTAL_LIMIT_EXCEEDED`, `PAYMENT_DECLINED` |
| **429 Too Many Requests** | Rate limit/cota. Inclua `Retry-After`. | categoria `RATE_LIMITED` |
| **500 Internal Server Error** | Bug ou falha inesperada. Nunca é culpa do cliente. | `INTERNAL_ERROR` |
| **502 Bad Gateway** | Uma dependência respondeu com erro ou com algo inválido. | `PAYMENT_PROVIDER_ERROR` |
| **503 Service Unavailable** | Indisponível temporariamente (manutenção, sobrecarga, circuit breaker aberto). Inclua `Retry-After` se souber. | `PAYMENT_PROVIDER_UNAVAILABLE` |
| **504 Gateway Timeout** | Uma dependência não respondeu a tempo. | `PAYMENT_PROVIDER_TIMEOUT` |

## 4.4 O debate 400 vs 422

Não há consenso universal; o que importa é **ser consistente e documentar**. A convenção do projeto:

| | 400 | 422 |
|---|---|---|
| Natureza | Sintaxe e forma | Semântica e negócio |
| Detectável sem consultar estado? | Sim (um JSON Schema pegaria) | Não, depende de dados/regras |
| Exemplo | `quantity: 0`, `customerId: ""` | total acima do limite do cliente, cartão recusado |
| Formato | Lista `errors` por campo | `code` específico + `details` |

O 422 hoje é definido no próprio HTTP (RFC 9110, §15.5.21, com o nome *Unprocessable Content*).
No Spring Framework 7 a constante é `HttpStatus.UNPROCESSABLE_CONTENT`; o antigo
`UNPROCESSABLE_ENTITY` está deprecado.

## 4.5 Pares que confundem

**401 vs 403** — "Quem é você?" vs "Sei quem você é, e a resposta é não."

**403 vs 404** — Se revelar que o recurso existe já é vazamento (ex.: `/users/{id}` de outro
tenant), responda 404.

**404 vs 200 com lista vazia** — `GET /orders/{id}` inexistente → 404. `GET /orders?customerId=x`
sem resultados → **200 com `[]`**. Busca sem resultado não é erro.

**409 vs 422** — 409 quando o problema é o **estado atual** do recurso (repetir depois que o estado
mudar pode funcionar). 422 quando a regra rejeita o **conteúdo** da requisição.

**409 vs 412** — mesma ideia (concorrência otimista); 412 quando a versão veio em header
condicional (`If-Match: "v3"`), 409 quando veio no corpo.

**500 vs 502 vs 503 vs 504** — 500 = nós quebramos. 502 = quem chamamos respondeu mal. 503 = não
estamos aceitando agora. 504 = quem chamamos não respondeu.

## 4.6 Nunca repasse o status da dependência

Se o provedor de pagamento responde **400** para você, isso significa que **você** montou a chamada
errado. Para o seu cliente, é um problema do servidor: **502** (ou 500).

```java
// SdkPaymentGateway: qualquer resposta inesperada do provedor vira PAYMENT_PROVIDER_ERROR (502)
return new IntegrationException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR, e);
```

Teste correspondente: `provedorDevolve400_paraNosEh502_naoErroDoCliente`.

A exceção é quando a resposta do provedor tem **significado de negócio** para o cliente (402 /
`card_declined` → `PAYMENT_DECLINED` 422). Aí você traduz para o **seu** vocabulário.

## 4.7 Retry: quem pode repetir o quê

| Status | Repetir? |
|---|---|
| 400, 401, 403, 404, 405, 415, 422 | Não, sem mudar a requisição |
| 409 | Só depois de reler o estado do recurso |
| 408, 429 | Sim, respeitando `Retry-After` |
| 500 | Com cautela (pode ser bug determinístico) |
| 502, 503, 504 | Sim, com backoff exponencial + jitter, **se a operação for idempotente** |

O projeto marca isso explicitamente com `"retryable": true|false` nas falhas de integração e envia
`Retry-After` quando o provedor informa.

> ⚠️ **504 em operação não idempotente é ambíguo.** No timeout do pagamento, o cliente não sabe se
> foi cobrado. Por isso a mensagem de `PAYMENT_PROVIDER_TIMEOUT` diz "pode ou não ter sido processado".
> A solução estrutural é uma **chave de idempotência** (ver techpill COM.2) e reconciliação.

## 4.8 Anti-patterns de status

- ❌ `200 OK` com `{"success": false, "error": "..."}`: quebra cache, retry, métricas e alertas.
- ❌ `500` para tudo que não é sucesso.
- ❌ `400` para bug do servidor ("deu NullPointerException, então a requisição estava errada").
- ❌ `404` para lista vazia.
- ❌ Inventar status (`499`, `520`) na sua API. Esses existem em proxies específicos, não em contratos.
- ❌ `401` quando o usuário está logado mas não tem permissão (é `403`).

---

### ✅ Resumo do capítulo

- 4xx = cliente, 5xx = servidor. Alertas olham 5xx.
- 400 sintaxe, 422 negócio, 409 estado, 404 inexistente, 412 precondição de header.
- 502/503/504 distinguem *como* a dependência falhou.
- Nunca repasse o status da dependência; traduza.
- Diga ao cliente se pode repetir (`retryable`, `Retry-After`).
