# 📨 Comunicação entre Serviços

[← Voltar ao índice](../README.md)

---

## COM.1 — Síncrono (REST/gRPC) vs assíncrono (mensageria) entre serviços

**❓ A decisão:** o serviço A precisa avisar o serviço B sobre algo. Chamada
síncrona esperando resposta, ou mensagem/evento assíncrono?

### 🔀 Síncrono — A chama B e espera

```java
@Service
public class OrderService {
    private final InventoryClient inventoryClient; // chamada HTTP síncrona

    public void createOrder(Order order) {
        inventoryClient.reserve(order.getItems()); // se Inventory estiver fora do ar, isso falha AGORA
        orderRepository.save(order);
    }
}
```

❌ Acopla a disponibilidade de `OrderService` à disponibilidade de
`InventoryService` **em tempo real** — se o inventário cair ou ficar lento,
pedidos param de ser criados, mesmo que a criação do pedido em si não
precisasse ser instantaneamente consistente com o estoque.

### 🔀 Assíncrono — via evento, com consistência eventual

```java
@Transactional
public void createOrder(Order order) {
    orderRepository.save(order);
    eventPublisher.publish(new OrderCreated(order.getId(), order.getItems())); // ver TRX.2, padrão Outbox
}

// InventoryService consome de forma independente, no seu próprio ritmo
@KafkaListener(topics = "order-created")
public void onOrderCreated(OrderCreated event) {
    inventoryService.reserve(event.items());
}
```

✅ `OrderService` continua funcionando normalmente mesmo se `InventoryService`
estiver temporariamente indisponível — a reserva de estoque acontece assim
que ele voltar, processando a fila acumulada.

### ✅ Veredito

Prefira comunicação **síncrona** quando o caller precisa do resultado
imediatamente para decidir o próximo passo (autorizar um pagamento antes de
confirmar o pedido — não dá para "confirmar depois"). Prefira **assíncrono**
quando a operação tolera consistência eventual e você quer que a
indisponibilidade temporária de um serviço não derrube os outros — isso é o
que realmente entrega o desacoplamento que a arquitetura de microsserviços
promete. Comunicação síncrona encadeada entre múltiplos serviços (A chama B
chama C chama D) soma a indisponibilidade de todos eles — é a armadilha mais
comum de quem migra de monolito para microsserviços sem repensar os pontos de
acoplamento.

---

## COM.2 — Idempotência em endpoints não naturalmente idempotentes

**❓ A decisão:** um cliente HTTP faz retry automático em timeout — o que
acontece se `POST /orders` for efetivamente executado duas vezes para a mesma
intenção do usuário?

### 🔀 Sem proteção — todo retry cria um novo recurso

```java
@PostMapping("/orders")
public OrderResponse create(@RequestBody CreateOrderRequest request) {
    return orderService.create(request); // um timeout de rede = um pedido duplicado
}
```

❌ Um timeout de rede não significa necessariamente que a primeira chamada
falhou no servidor — muitas vezes ela foi processada com sucesso, só a
resposta não voltou a tempo. O cliente, sem saber disso, tenta de novo.

### 🔀 Idempotency key fornecida pelo cliente

```java
@PostMapping("/orders")
public OrderResponse create(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreateOrderRequest request) {
    return idempotencyService.executeOnce(idempotencyKey, () -> orderService.create(request));
}

@Transactional
public <T> T executeOnce(String key, Supplier<T> operation) {
    return existingResult(key)
        .orElseGet(() -> {
            T result = operation.get();
            store(key, result); // mesma transação: grava o resultado atrelado à chave
            return result;
        });
}
```

✅ Uma segunda chamada com a mesma `Idempotency-Key` retorna o resultado da
primeira execução, sem repetir o efeito colateral (criar outro pedido, cobrar
duas vezes).

### ✅ Veredito

Qualquer endpoint com efeito colateral não naturalmente idempotente (criar
recurso, cobrar pagamento) e sujeito a retry de cliente em timeout —
praticamente todo cliente HTTP moderno faz retry automático — precisa de um
mecanismo de idempotência explícito. Sem isso, um timeout de rede (não uma
falha real do servidor) se traduz diretamente em cobrança duplicada ou pedido
duplicado: um dos bugs mais caros e mais evitáveis em sistemas de
pagamento/e-commerce.

---

## COM.3 — Resiliência: timeout + circuit breaker vs chamada "nua" a serviço externo

**❓ A decisão:** uma chamada a um serviço dependente (interno ou externo)
precisa de alguma proteção além do try/catch básico?

### 🔀 Chamada sem timeout nem proteção

```java
public InventoryStatus check(String sku) {
    return restTemplate.getForObject("http://inventory-service/stock/" + sku, InventoryStatus.class);
    // sem timeout configurado = pode esperar indefinidamente se o serviço travar (não cair — TRAVAR)
}
```

❌ Um serviço **lento** (não necessariamente fora do ar) prende
threads/conexões do chamador esperando indefinidamente. Sob carga, isso
esgota o pool de threads do chamador e o derruba também — propagando a falha
em cascata por toda a cadeia de chamadas, um serviço de cada vez.

### 🔀 Timeout explícito + circuit breaker com fallback

```java
@CircuitBreaker(name = "inventoryService", fallbackMethod = "fallbackStock")
@TimeLimiter(name = "inventoryService")
public CompletableFuture<InventoryStatus> check(String sku) {
    return CompletableFuture.supplyAsync(() ->
        restTemplate.getForObject("http://inventory-service/stock/" + sku, InventoryStatus.class));
}

public CompletableFuture<InventoryStatus> fallbackStock(String sku, Throwable t) {
    return CompletableFuture.completedFuture(InventoryStatus.unknown()); // degrada graciosamente
}
```

```yaml
resilience4j:
  timelimiter:
    instances:
      inventoryService:
        timeout-duration: 2s
  circuitbreaker:
    instances:
      inventoryService:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

### ✅ Veredito

Toda chamada de rede a um serviço externo/dependente precisa de **timeout
explícito**, sem exceção — nunca confie no timeout default do cliente HTTP,
que em várias bibliotecas é efetivamente infinito. Para dependências cuja
falha não deveria derrubar o chamador inteiro, adicione um circuit breaker
com um fallback **definido explicitamente** (mesmo que o fallback seja
"degradar com dado parcial ou cache antigo") — isso contém a falha na
fronteira do serviço em vez de deixá-la se propagar em cascata pelo resto do
sistema.
