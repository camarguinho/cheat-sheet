# 08 — Integrações, transações e execução assíncrona

[← Validação](07-validacao.md) · [Índice](README.md) · [Próximo: Logging e observabilidade →](09-logging-e-observabilidade.md)

---

## 8.1 Chamando serviços externos

### Traduza na fronteira (anti-corruption layer)

Nenhuma exceção de SDK/cliente HTTP deve atravessar a interface do seu gateway. A tabela de tradução
do projeto ([`SdkPaymentGateway`](../src/main/java/dev/cheatsheet/exceptions/payment/SdkPaymentGateway.java)):

| O provedor fez... | Viramos | Status para o nosso cliente |
|---|---|---|
| 402 / `card_declined` | `BusinessRuleException(PAYMENT_DECLINED)` | 422 |
| 503 + retry-after | `IntegrationException(PAYMENT_PROVIDER_UNAVAILABLE, retryAfter)` | 503 + `Retry-After` |
| `SocketTimeoutException` | `IntegrationException(PAYMENT_PROVIDER_TIMEOUT)` | 504 |
| 500, 400 ou qualquer outra coisa | `IntegrationException(PAYMENT_PROVIDER_ERROR)` | 502 |

Em todos os casos a exceção original fica em `getCause()`: aparece no log, nunca no corpo.

### `RestClient`: o que ele lança

| Situação | Exceção |
|---|---|
| Resposta 4xx | `HttpClientErrorException` (subclasses: `.NotFound`, `.Conflict`...) |
| Resposta 5xx | `HttpServerErrorException` |
| Erro de I/O: conexão recusada, timeout, DNS | `ResourceAccessException` (causa: `SocketTimeoutException`, `ConnectException`...) |
| Corpo não desserializável | `RestClientException` |

Todas as de status estendem `RestClientResponseException`, que dá acesso a status, headers e corpo
(`getResponseBodyAs(ProblemDetail.class)`).

```java
@Component
class CatalogClient {

    private final RestClient client;

    CatalogClient(RestClient.Builder builder) {
        this.client = builder
                .baseUrl("https://catalog.internal")
                .requestFactory(timeouts())                       // SEMPRE configure timeouts
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    throw switch (res.getStatusCode().value()) {
                        case 404 -> new NotFoundException(CatalogErrorCode.PRODUCT_NOT_FOUND);
                        case 503 -> new IntegrationException(CatalogErrorCode.CATALOG_UNAVAILABLE,
                                new IllegalStateException("catalog 503"));
                        default -> new IntegrationException(CatalogErrorCode.CATALOG_ERROR,
                                new IllegalStateException("catalog " + res.getStatusCode()));
                    };
                })
                .build();
    }

    Product get(String sku) {
        try {
            return client.get().uri("/products/{sku}", sku).retrieve().body(Product.class);
        } catch (ResourceAccessException e) {                     // rede/timeout
            throw new IntegrationException(CatalogErrorCode.CATALOG_TIMEOUT, e);
        }
    }
}
```

### Timeouts, retry e circuit breaker

- **Timeout é obrigatório.** Sem ele, uma dependência lenta esgota suas threads e o seu serviço cai junto.
- **Retry só para erro transitório e operação idempotente.** Use backoff exponencial com jitter e um
  limite pequeno (2 ou 3). Nunca repita 4xx.
- No Spring Framework 7 há suporte nativo: `@Retryable` (com `@EnableResilientMethods`) e `RetryTemplate`
  em `org.springframework.core.retry`. Resilience4j continua sendo a opção mais completa (circuit
  breaker, bulkhead, rate limiter).
- **Circuit breaker aberto** → falha imediata, traduzida para 503 com `Retry-After`.

```java
@Retryable(includes = IntegrationException.class, maxRetries = 2, delay = 200, multiplier = 2)
public Product get(String sku) { ... }
```

> Cuidado ao combinar retry com a categoria: só faz sentido repetir quando `isRetryable()` é
> verdadeiro. Repetir `PAYMENT_PROVIDER_ERROR` (502 por bug nosso) só multiplica a carga.

## 8.2 Transações e rollback

### A regra que pega todo mundo

```java
@Transactional
public void transfer(...) throws InsufficientFundsException {   // checked!
    debit(...);
    throw new InsufficientFundsException();   // ⚠️ COMMIT do débito. Checked não faz rollback.
}
```

Por padrão, `@Transactional` faz rollback em `RuntimeException` e `Error`, e **commit** em checked
exceptions. Se precisar de checked: `@Transactional(rollbackFor = Exception.class)`. Mais um motivo
para as exceções da aplicação serem unchecked.

### Engolir exceção dentro de transação

```java
@Transactional
public void process() {
    try {
        other.save();          // @Transactional(REQUIRED) que lança RuntimeException
    } catch (RuntimeException e) {
        log.warn("ignorando", e);
    }
}   // ⚠️ UnexpectedRollbackException no commit
```

O método interno participou da **mesma** transação e a marcou como *rollback-only*. Capturar a
exceção não desfaz essa marca. Ou deixe a exceção subir, ou use `REQUIRES_NEW` no interno (ver techpill TRX.1).

### Exceções de persistência do Spring

Com `@Repository` (e Spring Data), exceções de JDBC/JPA são traduzidas para a hierarquia
`DataAccessException`, independente do banco:

| Exceção | Significado típico | Mapeamento sugerido |
|---|---|---|
| `DuplicateKeyException` / `DataIntegrityViolationException` | unique/FK violada | 409 (`..._ALREADY_EXISTS`), traduzida no repositório/serviço |
| `OptimisticLockingFailureException` | versão desatualizada | 409 (ou 412 com ETag) |
| `PessimisticLockingFailureException`, `CannotAcquireLockException` | deadlock/lock timeout | retry; se persistir, 503 |
| `TransientDataAccessException` | falha transitória (conexão) | retry; 503 |
| `QueryTimeoutException` | query lenta | 503/504 + investigar |
| outras `NonTransientDataAccessException` | bug/schema | 500 |

> Traduza `DataIntegrityViolationException` **perto de onde você sabe qual constraint é**. No handler
> global você só saberia "alguma constraint falhou", e a mensagem dela tem nomes de tabela.

## 8.3 `@Async`, `@Scheduled` e mensageria

O `@RestControllerAdvice` só existe no ciclo de uma requisição HTTP. Fora dele, cada mecanismo tem sua regra:

| Mecanismo | Para onde vai a exceção | O que fazer |
|---|---|---|
| `@Async void` | `AsyncUncaughtExceptionHandler` (padrão: só loga) | Configure um via `AsyncConfigurer` que loga com contexto e incrementa métrica |
| `@Async` com `CompletableFuture` | Completa o future excepcionalmente | Quem consome trata (`exceptionally`, `handle`); desembrulhe `CompletionException` |
| `@Scheduled` | `ErrorHandler` do scheduler: tarefas recorrentes **logam e continuam** | Capture e trate dentro do job se precisar de algo além do log |
| Consumer Kafka/Rabbit | O container do listener: retry e depois DLQ (se configurado) | Classifique: validação/negócio = **não** retentável (DLQ direto); transitório = retentável |

A mesma `ErrorCategory` que define o status HTTP serve para decidir isso na mensageria:

```java
boolean retry = ex instanceof ApplicationException app && app.getErrorCode().category().isTransient();
```

**Mensagem venenosa:** uma mensagem que sempre falha (JSON inválido) bloqueia a partição se for
retentada para sempre. Sempre configure limite de tentativas + DLQ.

---

### ✅ Resumo do capítulo

- Traduza exceções de terceiros no adaptador; preserve a causa; não repasse o status deles.
- Timeout sempre; retry só para transitório e idempotente.
- `@Transactional` não faz rollback em checked; engolir exceção não desfaz *rollback-only*.
- Traduza `DataAccessException` perto do contexto.
- Fora do HTTP (`@Async`, `@Scheduled`, filas), o advice não existe: configure o handler de cada mecanismo.
