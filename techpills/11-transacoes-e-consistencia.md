# 🔁 Transações e Consistência

[← Voltar ao índice](../README.md)

---

## TRX.1 — `@Transactional`: escopo e propagation (`REQUIRED` vs `REQUIRES_NEW`)

**❓ A decisão:** um efeito colateral (auditoria, log de negócio) acontece
dentro de uma operação transacional maior. Ele deve fazer parte da mesma
transação, ou de uma independente?

### 🔀 Tudo na mesma transação (`REQUIRED`, o padrão)

```java
@Transactional
public void approveOrder(Order order) {
    order.approve();
    orderRepository.save(order);
    auditLogService.record(order); // se isso falhar, a aprovação inteira é desfeita
}
```

⚠️ Correto na maioria dos casos — mantém a atomicidade natural do caso de
uso: ou tudo acontece, ou nada acontece. Mas pergunte-se: "se o registro de
auditoria falhar por um motivo técnico transitório, a aprovação do pedido
deveria mesmo ser desfeita?" Às vezes a resposta é sim; às vezes não.

### 🔀 `REQUIRES_NEW` para efeitos colaterais que devem sobreviver independentemente

```java
@Service
public class AuditLogService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Order order) {
        auditRepository.save(new AuditEntry(order)); // commita numa transação própria,
        // sobrevive mesmo se a transação chamadora fizer rollback depois
    }
}
```

### ✅ Veredito

`REQUIRED` (o padrão do Spring) é correto na esmagadora maioria dos casos.
Use `REQUIRES_NEW` **deliberadamente**, e só, quando o efeito colateral tem
uma razão de negócio explícita para persistir independentemente do resultado
da transação principal (um registro de auditoria que precisa existir mesmo
que a operação de negócio falhe, por exemplo). Nunca use `REQUIRES_NEW` como
gambiarra para "resolver" um erro de lock/deadlock sem entender a causa raiz
— isso costuma esconder um problema de design maior (transações grandes
demais, ordem de acesso a recursos inconsistente entre threads).

---

## TRX.2 — Transação distribuída (2PC) vs padrão Outbox/Saga

**❓ A decisão:** uma operação precisa atualizar o banco local **e** publicar
um evento/mensagem de forma atômica — ou coordenar dados entre dois serviços
diferentes. Como garantir consistência sem uma transação distribuída
clássica?

### 🔀 Two-phase commit (XA) coordenando banco + fila

❌ Suporte a XA é frágil no ecossistema moderno de mensageria — brokers como
Kafka não suportam 2PC nativamente. Adiciona latência, um coordenador como
ponto único de falha, e a maioria das arquiteturas de microsserviços evita
2PC justamente por isso.

### 🔀 Padrão Outbox — atomicidade local, publicação assíncrona garantida

```java
@Transactional
public void createOrder(Order order) {
    orderRepository.save(order);
    outboxRepository.save(new OutboxEvent("OrderCreated", order.toEventPayload()));
    // ambos na MESMA transação local — atomicidade garantida sem 2PC
}

// processo separado (poller periódico, ou CDC via Debezium) lê a tabela outbox
// e publica no broker, marcando como publicado só após confirmação
```

✅ "Salvar o dado" e "garantir que o evento será publicado" acontecem de
forma efetivamente atômica, usando apenas a transação local do banco — sem
nenhum coordenador distribuído.

### 🔀 Saga, para fluxos multi-etapa entre serviços diferentes

Para um fluxo como "reservar estoque → cobrar → confirmar pedido", onde cada
etapa é um serviço diferente: uma Saga (orquestrada por um coordenador
central, ou coreografada via eventos entre os serviços) define, para cada
etapa, uma ação de **compensação** explícita a ser executada se uma etapa
posterior falhar (ex.: "cancelar reserva de estoque" compensa "reservar
estoque" se a cobrança falhar depois).

### ✅ Veredito

Evite transação distribuída (2PC) entre serviços/tecnologias diferentes — é
frágil, tem suporte limitado no ecossistema moderno e não escala bem. Use o
padrão **Outbox** sempre que precisar garantir, de forma atômica, que salvar
um dado e publicar um evento sobre ele aconteçam juntos. Use o padrão **Saga**
(com compensação explícita por etapa) para fluxos de negócio que atravessam
múltiplos serviços e não podem contar com uma transação única cobrindo tudo.

---

## TRX.3 — Transaction script "gordo" vs escopo transacional estreito

**❓ A decisão:** onde termina o que deveria estar dentro de um método
`@Transactional`?

### 🔀 Antipadrão — I/O externo dentro do escopo da transação

```java
@Transactional
public void checkout(CheckoutRequest request) {
    // valida estoque, calcula frete, aplica cupom...
    PaymentResult payment = paymentGateway.charge(request.toChargeRequest()); // I/O EXTERNO!
    if (!payment.isApproved()) {
        throw new PaymentDeclinedException();
    }
    orderRepository.save(Order.confirmed(request, payment));
}
```

❌ Chamar um serviço HTTP externo (gateway de pagamento) **dentro** de uma
transação de banco mantém a conexão/transação aberta pelo tempo inteiro da
chamada de rede. Sob carga, isso esgota o pool de conexões rapidamente,
porque cada requisição lenta prende uma conexão de banco esperando uma
resposta de rede que pode demorar segundos.

### 🔀 Correto — I/O externo fora da transação, escopo transacional estreito

```java
public void checkout(CheckoutRequest request) {
    PaymentResult payment = paymentGateway.charge(request.toChargeRequest()); // FORA da transação
    if (!payment.isApproved()) {
        throw new PaymentDeclinedException();
    }
    persistOrder(request, payment); // só a parte de banco entra em transação
}

@Transactional
protected void persistOrder(CheckoutRequest request, PaymentResult payment) {
    orderRepository.save(Order.confirmed(request, payment));
}
```

### ✅ Veredito

Mantenha o escopo de `@Transactional` o mais estreito possível — cobrindo
apenas as operações de banco que realmente precisam de atomicidade entre si.
**Nunca** faça uma chamada de rede/I/O externo (HTTP, fila, e-mail, SMS)
dentro do escopo de uma transação de banco aberta: isso prende uma conexão do
pool pela duração inteira da chamada externa, que é ordens de magnitude mais
lenta e mais instável que qualquer operação local de banco — uma das causas
mais comuns e mais evitáveis de esgotamento de connection pool sob carga (ver
[DB.4](10-persistencia-e-banco-de-dados.md#db4--connection-pool-sizing-hikaricp-quantas-conexões)).
