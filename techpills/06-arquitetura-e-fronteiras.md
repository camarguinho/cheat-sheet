# 🏗️ Arquitetura e Fronteiras

[← Voltar ao índice](../README.md)

---

## ARC.1 — *Package by layer* vs *package by feature*

**❓ A decisão:** como organizar os pacotes de um serviço: por camada técnica
ou por funcionalidade de negócio?

### 🔀 Opção A — *Package by layer*

```
com.empresa.app
├── controller
│   ├── OrderController.java
│   ├── CustomerController.java
├── service
│   ├── OrderService.java
│   ├── CustomerService.java
├── repository
│   ├── OrderRepository.java
│   ├── CustomerRepository.java
```

❌ Para tudo que outra camada precisa acessar, a classe precisa ser `public`
— `OrderService` é `public` mesmo que só `OrderController` devesse chamá-la.
Isso remove qualquer barreira de compilador contra acoplamento indevido entre
funcionalidades não relacionadas.
❌ Uma mudança de negócio em "pedidos" espalha o diff por três pacotes
distintos, longe uns dos outros.

### 🔀 Opção B — *Package by feature*

```
com.empresa.app
├── order
│   ├── OrderController.java
│   ├── OrderService.java        (package-private: só usado dentro do pacote order)
│   ├── OrderRepository.java
├── customer
│   ├── CustomerController.java
│   ├── CustomerService.java
│   ├── CustomerRepository.java
```

✅ Tudo relacionado a "pedidos" fica fisicamente junto — mudar uma regra de
negócio tem *blast radius* visível num único pacote. ✅ `OrderService` pode
ser package-private: o compilador barra qualquer tentativa de `customer`
chamar direto um detalhe interno de `order` que não foi pensado pra ser
público.

### ✅ Veredito

**Para qualquer serviço além de um protótipo pequeno, prefira *package by
feature*.** Ele aproxima código que muda junto (menor *blast radius* por
mudança, mais fácil extrair um módulo/serviço depois) e permite usar
visibilidade `package-private` como ferramenta real de encapsulamento — cada
feature expõe deliberadamente uma fachada pública (`Controller`/`Facade`) e
esconde o resto. *Package by layer* tende a forçar tudo a `public`, o que
silenciosamente convida acoplamento entre features que deveriam ser
independentes.

---

## ARC.2 — Domínio livre de framework vs domínio anêmico acoplado a Spring/JPA

**❓ A decisão:** as classes de domínio devem carregar anotações de framework
(`@Entity`, `@Component`) e depender diretamente do Spring/JPA, ou ficar
isoladas em POJOs puros, testáveis com `new` e JUnit?

### 🔀 Opção A — Domínio acoplado, pragmático

```java
@Entity
@Service // sim, entidade e serviço acoplados ao Spring/JPA diretamente
public class Order {
    @Id @GeneratedValue private Long id;
    @Autowired private transient DiscountCalculator calculator; // injeção direta na entidade
    // ...
}
```

⚠️ Rápido de escrever, familiar para o time, funciona bem em CRUDs simples
com pouca regra de negócio real. ❌ Para regra de negócio rica, testar exige
subir contexto Spring ou fazer *reflection hacks*; a lógica de domínio fica
misturada com preocupação de persistência/framework.

### 🔀 Opção B — Domínio livre de framework (hexagonal/portas e adaptadores)

```java
// módulo de domínio: zero import de Spring/JPA
public class Order {
    private final OrderId id;
    private final List<Item> items;

    public Money calculateDiscount(DiscountPolicy policy) {
        return policy.apply(this);
    }
}

// módulo de infraestrutura: adapta o domínio ao JPA
@Entity
@Table(name = "orders")
class OrderJpaEntity { /* mapeamento técnico, convertido de/para Order via mapper */ }

// porta definida pelo domínio, implementada pela infraestrutura
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    void save(Order order);
}
```

✅ `Order` é testável com `new Order(...)` puro, sem container nenhum, e
sobrevive a uma eventual troca de JPA por outra tecnologia de persistência
sem mudar uma linha da regra de negócio.

### ✅ Veredito

**Não existe resposta universal aqui — depende de quanta regra de negócio
real o domínio carrega.** Para CRUD simples com pouca lógica além de
validação de campo, o acoplamento direto a `@Entity`/Spring Data é pragmático
e mais rápido de manter — hexagonal ali é over-engineering. Para domínio com
regras de negócio complexas e de vida longa (motor de descontos, motor de
crédito, precificação), o custo de manter o domínio livre de framework se
paga rapidamente em testabilidade e resiliência a mudança de infraestrutura.
Decida **por módulo/bounded context**, olhando a complexidade real da regra
de negócio ali — nunca como mandato arquitetural do tipo "hexagonal em tudo",
que é, ele mesmo, um cheiro de dogmatismo.

---

## ARC.3 — Dependência circular entre módulos: como quebrar de verdade

**❓ A decisão:** o módulo `pedidos` precisa de algo de `pagamentos`, e
`pagamentos` precisa de algo de `pedidos`. Como resolver?

### 🔀 Solução ilusória — juntar tudo num módulo `commons`

```
commons/  (agora tanto pedidos quanto pagamentos dependem daqui,
           e o que causava o ciclo simplesmente mudou de endereço)
pedidos/  → depende de commons
pagamentos/ → depende de commons
```

❌ Isso não resolve o acoplamento — só o esconde atrás de um módulo que vira
uma "gaveta de tudo", e tende a crescer sem controle porque virou o caminho
de menor resistência para qualquer dependência incômoda.

### 🔀 Solução real — inversão de dependência via porta

```java
// módulo "pedidos" define a interface que ele precisa (a porta)
public interface PaymentAuthorization {
    boolean isAuthorized(OrderId orderId);
}

// módulo "pagamentos" implementa a porta definida por "pedidos"
// (pagamentos passa a depender de pedidos, não o contrário)
@Component
public class PaymentAuthorizationAdapter implements PaymentAuthorization {
    private final PaymentGateway gateway;
    public boolean isAuthorized(OrderId orderId) { return gateway.checkStatus(orderId).isApproved(); }
}
```

Ou, quando o acoplamento é sobre **fluxo/workflow**, não sobre chamar um
tipo:

```java
// "pedidos" publica um evento de domínio, sem saber quem escuta
public record OrderCreated(OrderId orderId, Money total) {}

// "pagamentos" escuta, sem "pedidos" precisar depender de "pagamentos"
@EventListener
public void on(OrderCreated event) { /* iniciar cobrança */ }
```

✅ Em ambos os casos, a dependência circular vira unidirecional: quem
realmente **define o conceito** (a interface, o evento) fica no módulo dono;
o outro lado depende dele, nunca o contrário.

### ✅ Veredito

**Nunca** resolva um ciclo de dependência criando um módulo "guarda-chuva"
compartilhado — isso só realoca o problema. Identifique qual lado é dono do
conceito, extraia uma interface (porta) nesse lado e inverta a dependência,
ou desacople via evento de domínio quando o que está circular é fluxo, não
tipo. Se o build já rejeita o ciclo (a maioria das ferramentas Maven/Gradle
com módulos multi-projeto rejeita ciclo em tempo de build), trate isso como
sinal correto de design quebrado — nunca como obstáculo de ferramenta a ser
contornado.
