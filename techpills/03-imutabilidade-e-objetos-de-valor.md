# 🧊 Imutabilidade e Objetos de Valor

[← Voltar ao índice](../README.md)

---

## VAL.1 — `record` vs Lombok `@Value` vs classe manual

**❓ A decisão:** como modelar um Value Object simples (`Money`, `Coordinates`,
um DTO de resposta)?

### 🔀 Opção A — Classe manual

```java
public final class Money {
    private final long cents;
    private final String currency;

    public Money(long cents, String currency) {
        this.cents = cents;
        this.currency = Objects.requireNonNull(currency);
    }

    public long getCents() { return cents; }
    public String getCurrency() { return currency; }

    @Override
    public boolean equals(Object o) { /* boilerplate manual, fácil de errar */ return false; }
    @Override
    public int hashCode() { return Objects.hash(cents, currency); }
    @Override
    public String toString() { return cents + " " + currency; }
}
```

❌ Boilerplate grande, propenso a erro humano (esquecer de incluir um campo no
`equals`, esquecer `final`, esquecer cópia defensiva).

### 🔀 Opção B — Lombok `@Value`

```java
@Value
public class Money {
    long cents;
    String currency;
}
```

✅ Elimina o boilerplate. ❌ Adiciona uma dependência de *annotation
processor* a todo consumidor de uma biblioteca — versões incompatíveis de
Lombok entre módulos já causaram builds quebrados em times reais. É uma
dependência de build, não só de runtime.

### 🔀 Opção C — `record` (Java 16+)

```java
public record Money(long cents, String currency) {
    public Money {
        Objects.requireNonNull(currency, "currency é obrigatório");
        if (cents < 0) throw new IllegalArgumentException("cents não pode ser negativo");
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }
}
```

✅ `equals`/`hashCode`/`toString` gerados corretamente pela linguagem, sem
dependência externa nenhuma. ✅ *Compact constructor* permite validar
invariantes na criação. ✅ Comunica imutabilidade e "isto é dado, não
comportamento com estado" de forma inequívoca para quem lê.

### ✅ Veredito

**Para Value Objects e DTOs simples, `record` é o padrão** em qualquer
codebase Java 16+ — é parte da linguagem, zero dependência extra para quem
consome sua biblioteca, e deixa a intenção de imutabilidade explícita no
tipo. Use Lombok `@Value` apenas em bases presas a Java < 16. **Nunca** use
`record` para entidades JPA (identidade mutável, precisa de construtor sem
argumentos e é alvo de *proxying* do Hibernate) nem para tipos que precisam
de herança.

---

## VAL.2 — Builder vs construtores telescópicos vs *static factory methods*

**❓ A decisão:** um objeto tem vários parâmetros de construção, alguns
opcionais. Como expor a criação dele?

### 🔀 Opção A — Construtores telescópicos

```java
public class HttpClientConfig {
    public HttpClientConfig(String baseUrl) { this(baseUrl, 5000); }
    public HttpClientConfig(String baseUrl, int timeoutMs) { this(baseUrl, timeoutMs, 3); }
    public HttpClientConfig(String baseUrl, int timeoutMs, int retries) { /* ... */ }
    // e se eu quiser timeoutMs + um header custom, mas não retries?
}
```

❌ Cresce combinatorialmente e vira ambíguo — dois `int` seguidos (`timeoutMs`,
`retries`) já são uma armadilha de troca de ordem que o compilador não pega.

### 🔀 Opção B — Builder

```java
public class HttpClientConfig {
    private final String baseUrl;
    private final int timeoutMs;
    private final int retries;

    private HttpClientConfig(Builder b) {
        this.baseUrl = b.baseUrl;
        this.timeoutMs = b.timeoutMs;
        this.retries = b.retries;
    }

    public static Builder builder(String baseUrl) { return new Builder(baseUrl); }

    public static class Builder {
        private final String baseUrl;
        private int timeoutMs = 5000; // default
        private int retries = 3;      // default

        private Builder(String baseUrl) { this.baseUrl = baseUrl; }
        public Builder timeoutMs(int v) { this.timeoutMs = v; return this; }
        public Builder retries(int v) { this.retries = v; return this; }
        public HttpClientConfig build() { return new HttpClientConfig(this); }
    }
}

// call site autoexplicativo, ordem não importa, defaults claros
HttpClientConfig config = HttpClientConfig.builder("https://api.exemplo.com")
    .timeoutMs(2000)
    .build();
```

✅ Cada parâmetro é nomeado no call site — impossível trocar a ordem por
engano; parâmetros opcionais ficam realmente opcionais.

### 🔀 Opção C — *Static factory methods* para os casos comuns

```java
public final class Money {
    public static Money zero(Currency currency) { /* ... */ return null; }
    public static Money ofCents(long cents, Currency currency) { /* ... */ return null; }
}
```

✅ Para 1-3 parâmetros ou casos de uso muito comuns, um nome descritivo
(`Money.zero(...)`) é mais legível que qualquer builder.

### ✅ Veredito

Regra prática por número de parâmetros: **1-3 parâmetros** → construtor
simples ou *static factory* com nome descritivo. **4+ parâmetros, com
opcionais ou tipos repetidos** (`String, String` ou `int, int`) → Builder.
**Nunca** ultrapasse 2-3 overloads telescópicos — a partir daí, o ganho de
legibilidade do Builder paga o código extra de escrevê-lo. As duas
abordagens não são excludentes: exponha *factory methods* para os casos
comuns e mantenha o Builder disponível para o caso totalmente customizado.

---

## VAL.3 — `equals`/`hashCode` em entidades JPA: identidade, chave de negócio ou todos os campos?

**❓ A decisão:** uma classe anotada com `@Entity` precisa de `equals`/`hashCode`
— com base em quê?

**📍 Contexto:** este é um dos bugs mais recorrentes e mais sutis em código
Java com JPA/Hibernate — geralmente descoberto só quando um `Set<Entity>` se
comporta de forma "impossível" em produção.

### 🔀 Opção A — Todos os campos (ex.: Lombok `@Data`)

```java
@Entity
@Data // gera equals/hashCode com TODOS os campos, incluindo mutáveis
public class Order {
    @Id private Long id;
    private String status;
    private BigDecimal total;
}
```

❌ `hashCode` muda conforme campos mutáveis mudam — um `Order` guardado num
`HashSet` "some" do set se `status` mudar depois de inserido (o balde de hash
calculado na inserção não é mais onde o objeto seria procurado).
❌ Quebra com *lazy loading*/proxies do Hibernate: comparar um proxy com a
entidade real pode falhar dependendo da implementação.

### 🔀 Opção B — Identidade padrão do `Object` (não sobrescrever)

❌ Duas instâncias representando a **mesma linha do banco** (uma carregada
antes, outra depois de um `merge()`/detach) são consideradas diferentes —
quebra comparações depois de round-trips pela camada de persistência.

### 🔀 Opção C — Chave de negócio, ou ID imutável atribuído na criação

```java
@Entity
public class Order {
    @Id
    private final UUID id; // atribuído no construtor, nunca muda

    protected Order() { this.id = null; } // exigido pelo JPA

    public Order(UUID id) {
        this.id = Objects.requireNonNull(id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode(); // constante: nunca muda, mesmo antes do id existir
    }
}
```

✅ `hashCode` constante evita o problema do "objeto some do Set"; `equals`
baseado só no ID (imutável, ideal gerado como UUID na aplicação, não pelo
banco) resolve identidade corretamente antes e depois de qualquer
persistência.

### ✅ Veredito

**Nunca** use `equals`/`hashCode` de todos os campos (nada de `@Data`/`@Value`
puro) em uma `@Entity`. Prefira uma **chave de negócio** natural quando ela
existir e for realmente imutável; na ausência dela, use o **ID substituto**
(idealmente um UUID atribuído na aplicação no momento da construção, não
gerado pelo banco) com `hashCode` constante e `equals` que trata `id == null`
como "nunca igual a nada, exceto identidade de objeto" (entidade transiente).
Essa é a receita consolidada por Vlad Mihalcea para JPA e vale como default
em qualquer projeto Hibernate.

---

## VAL.4 — Cópia defensiva vs coleção imutável exposta

**❓ A decisão:** uma classe guarda uma `List<T>` internamente. O que o getter
deve retornar?

### 🔀 Opção A — Retornar a referência interna direto

```java
public class ShoppingCart {
    private final List<Item> items = new ArrayList<>();

    public List<Item> getItems() { return items; } // referência interna!
}

// em outro lugar do código, sem querer:
cart.getItems().clear(); // corrompeu o estado interno do ShoppingCart
```

❌ Qualquer caller pode mutar o estado interno do objeto sem passar por
nenhuma regra de negócio — o encapsulamento é só de fachada.

### 🔀 Opção B — Imutabilidade construída na borda

```java
public class ShoppingCart {
    private final List<Item> items;

    public ShoppingCart(List<Item> items) {
        this.items = List.copyOf(items); // cópia defensiva + imutável, uma vez só
    }

    public List<Item> getItems() { return items; } // seguro: já é imutável
}
```

✅ A cópia acontece uma única vez, na construção — o getter não paga custo
nenhum a cada chamada e o objeto é imutável de ponta a ponta.

### 🔀 Opção C — View imutável sobre estado que de fato muda

```java
public class ShoppingCart {
    private final List<Item> items = new ArrayList<>(); // estado que muda de propósito

    public void addItem(Item item) { items.add(item); }

    public List<Item> getItems() {
        return Collections.unmodifiableList(items); // view: ainda reflete mudanças futuras
    }
}
```

⚠️ É uma *view*, não uma cópia — se `items` mudar depois, a lista retornada
anteriormente muda junto. Útil só quando isso é intencional.

### ✅ Veredito

**Nunca exponha uma coleção mutável interna diretamente por um getter.**
Quando o objeto é (ou deveria ser) imutável, prefira construir com
`List.copyOf`/`Set.copyOf` uma única vez no construtor — mais barato e mais
seguro que copiar a cada chamada do getter. Reserve
`Collections.unmodifiableList` para os casos, mais raros, em que o estado
interno é intencionalmente mutável ao longo da vida do objeto e você só quer
impedir que o **caller externo** mute — mas documente que é uma view viva,
não uma foto congelada.
