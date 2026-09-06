# 📐 Design de API e Contratos

[← Voltar ao índice](../README.md)

---

## API.1 — Expor entidade de domínio/JPA na REST vs DTO na borda

**❓ A decisão:** um controller REST deve retornar a `@Entity` diretamente ou
mapear para um DTO?

### 🔀 Opção A — Retornar a entidade diretamente

```java
@GetMapping("/orders/{id}")
public Order getOrder(@PathVariable Long id) {
    return orderRepository.findById(id).orElseThrow();
}
```

❌ Qualquer rename de coluna no banco muda silenciosamente o contrato público
da API. ❌ Serializar uma associação `@OneToMany` *lazy* fora da sessão
Hibernate lança `LazyInitializationException` — ou, se configurado para
serializar de qualquer jeito, dispara N+1 queries dentro da serialização.
❌ Vaza campos internos que nunca deveriam sair (auditoria, chaves
estrangeiras internas, outras relações não pensadas para consumo externo).

### 🔀 Opção B — DTO dedicado na borda

```java
public record OrderResponse(Long id, String status, BigDecimal total, List<String> itemNames) {}

@Mapper(componentModel = "spring")
public interface OrderMapper {
    @Mapping(target = "itemNames", expression = "java(mapItemNames(order))")
    OrderResponse toResponse(Order order);

    default List<String> mapItemNames(Order order) {
        return order.getItems().stream().map(Item::getName).toList();
    }
}

@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable Long id) {
    Order order = orderRepository.findById(id).orElseThrow();
    return orderMapper.toResponse(order);
}
```

✅ O contrato de API é desenhado deliberadamente, independente do schema do
banco — pode evoluir em ritmos diferentes.

### ✅ Veredito

**Nunca retorne entidades JPA diretamente de um endpoint REST.** Use um DTO
dedicado, gerado por um mapper em tempo de compilação (MapStruct) para
qualquer objeto com mais de 2-3 campos — mapeamento manual escrito à mão tende
a divergir silenciosamente do modelo com o tempo. O custo do mapeamento
extra é desprezível perto do custo de um contrato de API acoplado ao schema
de persistência.

---

## API.2 — Interface vs classe abstrata como ponto de extensão (SPI)

**❓ A decisão:** você está desenhando um ponto de extensão de uma biblioteca —
consumidores vão plugar suas próprias implementações (`PaymentGateway`,
`ExportFormat`). Interface ou classe abstrata?

### 🔀 Opção A — Classe abstrata

```java
public abstract class PaymentGateway {
    protected abstract void authenticate();
    protected abstract PaymentResult doPay(PaymentRequest request);

    public final PaymentResult pay(PaymentRequest request) { // template method
        authenticate();
        return doPay(request);
    }
}
```

⚠️ Correto **só** quando você precisa de um algoritmo com esqueleto fixo
(template method) e estado real compartilhado entre implementações. ❌ Impõe
herança única — o consumidor que já estende outra classe (framework, outra
lib) fica travado.

### 🔀 Opção B — Interface, com *default methods* para evolução

```java
public interface PaymentGateway {
    PaymentResult pay(PaymentRequest request);

    // adicionado numa versão posterior, sem quebrar quem já implementa a interface
    default boolean supportsRefund() {
        return false;
    }
}
```

✅ Não consome a única herança do consumidor; pode ser implementada por uma
`enum`, um lambda (se for *functional interface*), ou combinada com outras
interfaces. ✅ `default method` permite adicionar capacidade nova à interface
depois, sem quebrar implementações existentes — foi assim que o próprio JDK
evoluiu `Comparator`/`Collection` no Java 8.

### ✅ Veredito

**Prefira interface como ponto de extensão, por padrão.** Use `default
methods` para evoluir o contrato de forma não destrutiva. Reserve classe
abstrata para quando você genuinamente precisa de um *template method* com
estado compartilhado real entre implementações — e, mesmo aí, avalie se
composição (uma `Strategy` injetada numa classe concreta) não resolve melhor
que herança: "favoreça composição sobre herança" continua valendo quando a
única razão para herdar é reaproveitar código, não modelar um "é-um"
genuíno.

---

## API.3 — Versionamento de contrato público: semver + `@Deprecated`

**❓ A decisão:** como evoluir a API pública de uma biblioteca sem quebrar quem
já a consome?

### 🔀 Abordagem incorreta — mudar comportamento silenciosamente

```java
// v1.2.0
public BigDecimal calculateDiscount(Order order) {
    return order.getTotal().multiply(BigDecimal.valueOf(0.10)); // 10% flat
}

// v1.3.0 — "corrigido" para uma regra mais sofisticada, mesma assinatura
public BigDecimal calculateDiscount(Order order) {
    return tieredDiscountEngine.calculate(order); // comportamento mudou sem aviso
}
```

❌ Consumidores que dependiam do comportamento anterior (mesmo que "errado")
quebram silenciosamente numa atualização de PATCH/MINOR — viola o Principle
of Least Astonishment e o próprio semver.

### 🔀 Abordagem correta — depreciar, nunca remover ou mudar sem aviso

```java
/**
 * @deprecated desde 1.3.0, será removido em 2.0.0.
 *             Use {@link #calculateDiscount(Order, DiscountPolicy)}.
 */
@Deprecated(since = "1.3.0", forRemoval = true)
public BigDecimal calculateDiscount(Order order) {
    return calculateDiscount(order, DiscountPolicy.FLAT_10_PERCENT);
}

public BigDecimal calculateDiscount(Order order, DiscountPolicy policy) {
    return policy.apply(order);
}
```

✅ O comportamento antigo continua existindo e funcionando; o novo é opt-in;
o removal fica agendado e comunicado.

### ✅ Veredito

Siga semver à risca: qualquer mudança de assinatura, remoção de método ou
mudança de comportamento observável que possa quebrar um consumidor
compilando/funcionando é **MAJOR**. Nunca remova um método público sem um
ciclo de depreciação — marque com `@Deprecated(since=..., forRemoval=true)`,
documente a alternativa no Javadoc, e mantenha o caminho antigo funcional por
pelo menos um ciclo MINOR/MAJOR inteiro. Mudança de comportamento em método
existente, mesmo mantendo a assinatura, é tão breaking quanto mudar a
assinatura — trate como tal (novo overload/novo nome + depreciação do antigo).

---

## API.4 — Bean Validation no DTO vs invariante no construtor do domínio

**❓ A decisão:** onde validar dados de entrada — anotações `jakarta.validation`
no DTO da borda, ou checagem explícita no construtor/factory do objeto de
domínio?

### 🔀 Opção A — Só Bean Validation no DTO

```java
public record CreateOrderRequest(
    @NotNull @Size(min = 1) List<@Valid ItemRequest> items,
    @NotNull UUID customerId
) {}
```

⚠️ Ótimo para validação estrutural/formato na borda HTTP — rápido, declarativo,
gera 400 automaticamente. ❌ Insuficiente sozinho: se o mesmo `Order` puder
ser construído a partir de uma fila de mensagens, um job em batch, ou um
teste, nenhum desses caminhos passa pelas anotações do DTO — o domínio fica
vulnerável a estado inválido vindo de qualquer entrada que não seja a REST.

### 🔀 Opção B — Invariante garantida no próprio domínio

```java
public class Order {
    private final List<Item> items;

    public Order(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Um pedido precisa de ao menos um item");
        }
        this.items = List.copyOf(items);
    }
}
```

✅ Não importa de onde `Order` é construído — REST, fila, batch, teste — é
**impossível** existir um `Order` sem item. A regra de negócio mora onde
pertence: no domínio.

### ✅ Veredito

**Use as duas, com papéis diferentes.** Bean Validation no DTO de entrada é
uma conveniência de UX/fail-fast na borda (feedback rápido, HTTP 400
automático) — não é a fonte de verdade. A fonte de verdade das invariantes de
negócio é o próprio construtor/factory do objeto de domínio, porque ele
precisa proteger a regra **independentemente do ponto de entrada**. Se você
só validar no DTO, todo novo ponto de entrada (uma fila nova, um job batch
novo) reintroduz o risco de estado inválido.
