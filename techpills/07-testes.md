# 🧪 Testes

[← Voltar ao índice](../README.md)

---

## TST.1 — Mockist (London School) vs Classicist (Detroit School)

**❓ A decisão:** ao testar `OrderService`, que depende de `PricingCalculator`
(lógica pura) e de `PaymentGateway` (chamada de rede externa), o que deve ser
mockado?

### 🔀 Estilo mockist — tudo é mock

```java
@Test
void deveProcessarPedido() {
    PricingCalculator pricing = mock(PricingCalculator.class);
    PaymentGateway gateway = mock(PaymentGateway.class);
    when(pricing.calculate(any())).thenReturn(Money.of(100, "BRL"));
    when(gateway.charge(any())).thenReturn(PaymentResult.approved());

    OrderService service = new OrderService(pricing, gateway);
    service.process(order);

    verify(pricing).calculate(order);   // testando COMO, não O QUE
    verify(gateway).charge(any());
}
```

❌ O teste passa a verificar a sequência exata de chamadas internas, não o
resultado observável. Um refatoro legítimo (trocar a ordem de duas chamadas
independentes, extrair um método) quebra o teste sem que nenhum
comportamento real tenha mudado — o famoso teste "frágil que grita a cada
refactor".

### 🔀 Estilo classicist — real onde é barato, mock só na fronteira cara/externa

```java
@Test
void deveProcessarPedido() {
    PricingCalculator pricing = new PricingCalculator(); // real: é lógica pura, determinística, rápida
    PaymentGateway gateway = mock(PaymentGateway.class);  // mock: é I/O externo de verdade
    when(gateway.charge(any())).thenReturn(PaymentResult.approved());

    OrderService service = new OrderService(pricing, gateway);
    OrderResult result = service.process(order);

    assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED); // testando O QUE, não COMO
}
```

✅ O teste sobrevive a qualquer refatoração interna que preserve o
comportamento observável — só quebra se o comportamento de verdade mudar.

### ✅ Veredito

**Default classicista:** use colaboradores reais (ou fakes simples em
memória) sempre que forem baratos, determinísticos e rápidos — objetos de
valor, cálculos puros, repositórios em memória. **Reserve mock** para
fronteiras genuinamente caras, lentas, não determinísticas ou externas
(cliente HTTP, relógio, gerador aleatório, SDK de terceiro, banco de dados
numa suíte que precisa ser rápida). Mockar demais produz testes que
verificam *interação*, não *resultado* — e testes de interação são os que
mais quebram sem motivo em refactors saudáveis.

---

## TST.2 — Testcontainers vs H2 in-memory em testes de integração

**❓ A decisão:** testar a camada de repositório JPA contra o banco real
(via container) ou contra um banco em memória (H2 em modo de compatibilidade)?

### 🔀 Opção A — H2 in-memory

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY) // usa H2 em vez do Postgres
class OrderRepositoryTest {
    @Autowired OrderRepository repository;

    @Test
    void deveBuscarPedidosPorCliente() {
        // roda rápido, sem Docker — mas roda contra H2, não contra o Postgres real de produção
    }
}
```

✅ Início instantâneo, zero dependência de Docker no ambiente de CI/local.
❌ H2 diverge do dialeto real em pontos que mordem exatamente onde dói mais:
colunas `JSON`/`JSONB`, funções de window, sensibilidade a maiúsculas/minúsculas
em identificadores, comportamento de sequence, semântica de lock. Um teste
verde no H2 não garante nada sobre o comportamento no Postgres de produção.

### 🔀 Opção B — Testcontainers com o banco real

```java
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withReuse(true); // reaproveita o container entre classes de teste, evita restart a cada suíte

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired OrderRepository repository;

    @Test
    void deveBuscarPedidosPorClienteUsandoJsonb() {
        // roda contra um Postgres real — pega comportamento de dialeto que H2 não reproduz
    }
}
```

✅ O que passa aqui é uma garantia real sobre o banco de produção, incluindo
consultas nativas, `JSONB`, funções específicas do dialeto.

### ✅ Veredito

**Prefira Testcontainers com o motor de banco real** para testes de
integração da camada de persistência — a garantia de correção vale o custo
de Docker + inicialização mais lenta, especialmente porque esse custo é
mitigável com reuse de container entre testes (`withReuse(true)` ou um
container compartilhado no nível da suíte, evitando subir um container por
método de teste). Reserve H2/dublê em memória apenas para testes muito
estreitos e rápidos que não tocam nada sensível a dialeto (JPQL simples, sem
SQL nativo).

---

## TST.3 — Mock vs Stub vs Fake: quando usar cada test double

**❓ A decisão:** existem pelo menos três tipos distintos de "dublê de teste"
(taxonomia de Martin Fowler) — usá-los como sinônimos é a fonte de boa parte
dos testes ruins que existem por aí.

### 🔀 Stub — retorna dado combinado, sem verificar interação

```java
@Test
void deveAplicarDescontoParaClienteVip() {
    CustomerRepository stub = mock(CustomerRepository.class); // usado como stub
    when(stub.findById(any())).thenReturn(Optional.of(vipCustomer()));

    PricingService pricing = new PricingService(stub);
    Money total = pricing.calculateFor(order, customerId);

    assertThat(total).isEqualTo(Money.of(90, "BRL")); // asserção no ESTADO/RESULTADO
}
```
Aqui, mesmo usando `Mockito.mock`, o **papel** desempenhado é o de um stub:
ele só fornece dado, e o teste nunca faz `verify(...)` sobre ele.

### 🔀 Mock — a interação em si é o que está sendo testado

```java
@Test
void deveNotificarClienteAoAprovarPedido() {
    NotificationSender mock = mock(NotificationSender.class);
    OrderService service = new OrderService(mock);

    service.approve(order);

    // aqui a interação É o comportamento observável — não há outro jeito de
    // constatar que a notificação foi disparada, já que o método é void
    verify(mock).send(order.getCustomerEmail(), any());
}
```
Correto usar um mock verificado quando a ação tem efeito colateral sem
retorno observável de outra forma.

### 🔀 Fake — implementação real e leve, sem framework de mock

```java
public class InMemoryOrderRepository implements OrderRepository {
    private final Map<OrderId, Order> storage = new HashMap<>();
    public void save(Order order) { storage.put(order.getId(), order); }
    public Optional<Order> findById(OrderId id) { return Optional.ofNullable(storage.get(id)); }
}

@Test
void devePersistirEBuscarPedido() {
    OrderRepository fake = new InMemoryOrderRepository();
    OrderService service = new OrderService(fake);

    service.create(newOrderRequest());

    assertThat(fake.findById(orderId)).isPresent(); // comportamento real, sem mock
}
```
✅ Tende a produzir os testes mais robustos a refactor dos três, porque
exercita comportamento real de ponta a ponta em vez de expectativas
configuradas manualmente.

### ✅ Veredito

Use **Stub** quando só precisa que um colaborador devolva um dado para o
teste prosseguir, e a asserção final é sobre o resultado/estado. Use
**Mock** (com `verify`) **apenas** quando a própria interação é o
comportamento observável que você quer garantir — tipicamente, um efeito
colateral sem retorno que revele se ele aconteceu. Use **Fake** — uma
implementação real e leve da interface — quando quiser testes realistas e
resistentes a refactor sem o overhead de configurar um framework de mock;
para repositórios e gateways simples, um Fake in-memory costuma valer mais
que dezenas de `when(...).thenReturn(...)` espalhados pela suíte. Por
padrão, prefira Stub/Fake; reserve Mock+`verify` para o caso específico em
que não existe outro jeito de observar que a ação aconteceu.
