# 02 — Desenhando as exceções da aplicação

[← Fundamentos](01-fundamentos-de-excecoes-em-java.md) · [Índice](README.md) · [Próximo: Error codes →](03-error-codes.md)

---

## 2.1 A distinção que organiza tudo: esperado vs inesperado

| | Erro **esperado** | Erro **inesperado** |
|---|---|---|
| Exemplo | Pedido não existe; limite excedido; provedor fora do ar | NPE; índice fora da faixa; invariante violada |
| Faz parte do contrato? | **Sim**, está documentado | Não, é um bug |
| Quem lança | Seu código, de propósito | A JVM, uma lib, um `requireNonNull` |
| Resposta ao cliente | Específica: status certo, code, mensagem útil | Genérica: 500, sem detalhes |
| Log | Sem stack trace (4xx) ou com (5xx de integração) | **Sempre** com stack trace, nível ERROR |
| Alerta | Por taxa/métrica | Cada ocorrência merece investigação |

No projeto, erro esperado = subclasse de
[`ApplicationException`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ApplicationException.java).
Todo o resto é inesperado e cai na rede de segurança `@ExceptionHandler(Exception.class)`.

## 2.2 Quando criar uma exceção própria?

Crie uma exceção nova **somente** se pelo menos uma destas for verdade:

1. Algum caller vai querer **capturá-la especificamente** (`catch (PaymentDeclinedException e)`), e
   tratá-la de forma diferente das outras.
2. Ela precisa **carregar dados** estruturados (código, limite, valor tentado).
3. Ela marca uma **fronteira** de abstração (traduz a exceção de uma lib para a linguagem do domínio).

Se nenhuma for verdade, use uma exceção padrão ou uma das existentes com outro `ErrorCode`.

## 2.3 Três formas de organizar a hierarquia

### Opção A — Uma classe por erro

```java
class OrderNotFoundException extends RuntimeException { ... }
class OrderLimitExceededException extends RuntimeException { ... }
class OrderAlreadyCancelledException extends RuntimeException { ... }
class PaymentDeclinedException extends RuntimeException { ... }
// ... 80 classes depois
```

✅ `catch` muito específico.
❌ Explosão de classes; cada uma precisa de um `@ExceptionHandler` ou de `@ResponseStatus`.
❌ Nada garante consistência de formato entre elas.

### Opção B — Uma única exceção + código

```java
throw new AppException(OrderErrorCode.ORDER_NOT_FOUND, id);
```

✅ Um handler resolve tudo; o catálogo de códigos é o contrato.
❌ `catch` seletivo vira `if (e.getCode() == ...)`.

### Opção C — Híbrida: base + subclasses por **categoria** + código ✅ (usada no projeto)

```java
throw new NotFoundException(OrderErrorCode.ORDER_NOT_FOUND, id);
throw new BusinessRuleException(OrderErrorCode.ORDER_TOTAL_LIMIT_EXCEEDED, details, total, limit);
throw new ConflictException(OrderErrorCode.ORDER_INVALID_STATE, details, id, status, action);
throw new IntegrationException(PaymentErrorCode.PAYMENT_PROVIDER_TIMEOUT, cause);
```

```
RuntimeException
└── ApplicationException (abstract)   ← carrega ErrorCode + details
    ├── NotFoundException             ← categoria NOT_FOUND
    ├── ConflictException             ← categoria CONFLICT
    ├── BusinessRuleException         ← categoria BUSINESS_RULE
    └── IntegrationException          ← DEPENDENCY_FAILURE | DEPENDENCY_TIMEOUT | UNAVAILABLE
                                         + causa obrigatória + retryAfter
```

✅ Poucas classes, leitura clara no ponto do `throw`.
✅ `catch (IntegrationException e)` é possível onde faz sentido (ex.: fallback).
✅ O **código** continua sendo a fonte da verdade. O construtor recusa combinações
incoerentes, como `new NotFoundException(ORDER_TOTAL_LIMIT_EXCEEDED)` (falha imediata, coberta por teste).

## 2.4 O domínio não conhece HTTP

Uma tentação comum:

```java
@ResponseStatus(HttpStatus.NOT_FOUND)                     // ❌ HTTP no domínio
public class OrderNotFoundException extends RuntimeException {}

throw new ResponseStatusException(HttpStatus.CONFLICT);   // ❌ HTTP no service
```

Problemas:

- O mesmo serviço é chamado por um consumer Kafka, um job batch ou um endpoint gRPC. "404" não
  significa nada lá.
- Trocar o status (ex.: 400 → 422) exige mexer no domínio.
- Testes de domínio passam a depender do `spring-web`.

**A solução do projeto:** cada `ErrorCode` declara uma
[`ErrorCategory`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ErrorCategory.java)
(`NOT_FOUND`, `CONFLICT`, `BUSINESS_RULE`...). Só a camada web traduz categoria em status, num
único lugar: [`HttpStatusMapper`](../src/main/java/dev/cheatsheet/exceptions/shared/web/HttpStatusMapper.java).

```
Domínio:    ErrorCode ──► ErrorCategory           (sem dependência de spring-web)
Web:                      ErrorCategory ──► HttpStatus   (HttpStatusMapper)
Mensageria:               ErrorCategory ──► retry? DLQ?  (outro mapper)
```

> `ResponseStatusException` tem seu lugar: **dentro do controller**, para um erro pontual que
> só existe na borda HTTP (ex.: header obrigatório ausente num endpoint específico).

## 2.5 Propagar, embrulhar ou traduzir?

| Ação | Quando |
|---|---|
| **Propagar** (não fazer nada) | Você não tem o que acrescentar. É o caso mais comum. |
| **Traduzir** (catch + throw de outro tipo, com causa) | Na **fronteira** com outra abstração: SDK, driver, cliente HTTP. *Effective Java*, item 73. |
| **Tratar** (catch e seguir) | Você tem uma ação real: fallback, retry, valor default **documentado**. |
| **Enriquecer** | Raro. Quando falta contexto para diagnosticar (ex.: qual item do lote falhou). |

O exemplo canônico do projeto é o adaptador de pagamento: o **único** lugar que conhece as exceções do SDK.

```java
// SdkPaymentGateway.java
try {
    return sdk.charge(paymentToken, amount);
} catch (SocketTimeoutException e) {
    throw new IntegrationException(PaymentErrorCode.PAYMENT_PROVIDER_TIMEOUT, e);
} catch (PaymentSdkException e) {
    throw translate(e);   // 402 → PAYMENT_DECLINED, 503 → UNAVAILABLE + Retry-After, resto → 502
}
```

Note o que o `OrderService` **não** tem: nenhum `try/catch`. Ele lança e deixa subir.

## 2.6 Ausência de valor: `Optional`, `null` ou exceção?

```java
// Repositório: ausência é resultado normal de uma consulta
Optional<Order> findById(UUID id);

// Caso de uso: aqui, ausência de um recurso identificado É um erro (404)
public Order get(UUID id) {
    return repository.findById(id)
            .orElseThrow(() -> new NotFoundException(OrderErrorCode.ORDER_NOT_FOUND, id));
}

// Listagem: vazio NÃO é erro. É 200 com [].
public List<Order> listByCustomer(String customerId) { ... }
```

Quem decide se a ausência é erro é quem conhece o **contexto** (o caso de uso), não quem busca (o repositório).
Para a discussão completa, ver a techpill [ERR.2](../../techpills/02-tratamento-de-erros.md).

## 2.7 Atomicidade de falha: valide antes do efeito colateral

*Effective Java*, item 76: uma operação que falha deve deixar o objeto no estado anterior.
No mundo distribuído, isso vira: **verifique tudo o que é barato e local antes de chamar algo caro e externo.**

```java
public Order pay(UUID id, String paymentToken) {
    Order order = get(id);
    order.ensureCanBePaid();   // 409 aqui, ANTES de cobrar o cartão do cliente
    String paymentId = paymentGateway.charge(paymentToken, order.total());
    order.markPaid(paymentId);
    return repository.save(order);
}
```

Sem a linha `ensureCanBePaid()`, um pedido cancelado seria **cobrado** e só depois falharia.
Há um teste específico para isso (`pagarPedidoCancelado_409_semChamarOProvedor`).

## 2.8 Mensagens de exceção

Duas audiências diferentes:

| | Mensagem para o **cliente** | Mensagem para o **log** |
|---|---|---|
| Onde | `detail` do Problem Detail | `getMessage()` + causa + stack |
| Conteúdo | O que aconteceu e o que fazer | Tudo o que ajuda a diagnosticar |
| Proibido | SQL, nomes de classe, caminhos, PII, segredos | Segredos e PII (sim, no log também) |

No projeto, o `messageTemplate` de cada `ErrorCode` é, por contrato, **seguro para o cliente**.
Detalhes técnicos ficam na **causa** (que nunca é serializada). Para erros inesperados, a
mensagem original **nunca** sai: o cliente recebe só o texto genérico e o `correlationId`.

Inclua na mensagem os dados que capturam a falha (*Effective Java*, item 75): "Pedido 3f2b... não
encontrado" é muito melhor que "Not found".

## 2.9 Anti-patterns

```java
// ❌ 1. Engolir
try { ... } catch (Exception e) { }

// ❌ 2. Logar e relançar (o mesmo erro aparece 3x no log, uma por camada)
catch (Exception e) { log.error("erro", e); throw e; }

// ❌ 3. Exceção genérica sem significado
throw new RuntimeException("Erro");

// ❌ 4. Perder a causa
catch (IOException e) { throw new AppException("falhou"); }

// ❌ 5. catch (Exception) em cada camada "por segurança"
// ❌ 6. Retornar null/-1/false para sinalizar erro
// ❌ 7. e.getMessage() direto na resposta HTTP
// ❌ 8. Exceção como controle de fluxo normal
try { return repo.findById(id).get(); } catch (NoSuchElementException e) { return create(); }

// ❌ 9. Status HTTP no domínio (@ResponseStatus / ResponseStatusException no service)
// ❌ 10. Repassar o status do serviço externo para o seu cliente (400 do provedor ≠ 400 seu)
```

---

### ✅ Resumo do capítulo

- Separe **esperado** (contrato, `ApplicationException`) de **inesperado** (bug, 500 genérico).
- Hierarquia enxuta: base + categoria + **código**.
- Domínio fala em categoria; só a web fala em HTTP status.
- Traduza na fronteira, preservando a causa. No resto, propague.
- Valide o que é local antes de efeitos externos.
- Mensagem para o cliente ≠ mensagem para o log.
