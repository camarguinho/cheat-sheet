# 💉 Injeção de Dependência

[← Voltar ao índice](../README.md)

---

## DI.1 — Biblioteca reutilizável: `@Autowired` em campo vs injeção via construtor

**❓ A decisão:** ao criar um componente reutilizável (biblioteca, starter, módulo
compartilhado entre times), a classe deve declarar suas dependências com
`@Autowired` em campo/setter, ou receber tudo pelo construtor, sem nenhuma
anotação de framework?

**📍 Contexto:** você está escrevendo `NotificationService`, que vai virar um
`.jar` consumido por vários times — alguns em Spring Boot, outros em batch
jobs sem container nenhum, outros ainda escrevendo testes de unidade puros.

### 🔀 Opção A — `@Autowired` em campo (o jeito "rápido")

```java
public class NotificationService {

    @Autowired
    private EmailSender emailSender;

    @Autowired
    private SmsSender smsSender;

    public void notify(User user, String message) {
        emailSender.send(user.getEmail(), message);
    }
}
```

❌ Só funciona dentro de um `ApplicationContext` do Spring — todo consumidor da
biblioteca é forçado a usar Spring, mesmo em um batch job simples.
❌ Impossível instanciar em teste de unidade sem reflection (`ReflectionTestUtils`)
ou sem subir um `ApplicationContext`.
❌ Campos não podem ser `final` — nada impede um estado inconsistente
(objeto "meio construído") entre o `new` e a injeção do container.
❌ Dependência obrigatória fica implícita — só aparece um `NullPointerException`
em runtime se alguém instanciar a classe fora do Spring.

### 🔀 Opção B — Injeção via construtor, sem anotações de framework

```java
public class NotificationService {

    private final EmailSender emailSender;
    private final SmsSender smsSender;

    // Spring 4.3+: com um único construtor, @Autowired é opcional e implícito.
    public NotificationService(EmailSender emailSender, SmsSender smsSender) {
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender é obrigatório");
        this.smsSender = Objects.requireNonNull(smsSender, "smsSender é obrigatório");
    }

    public void notify(User user, String message) {
        emailSender.send(user.getEmail(), message);
    }
}
```

✅ Funciona igual em Spring, em Quarkus, em um `main()` cru ou em teste de
unidade: `new NotificationService(fakeEmail, fakeSms)`.
✅ Campos `final` garantem que o objeto nunca existe em estado inválido.
✅ Dependência obrigatória é explícita na assinatura — o compilador barra quem
esquecer de passar algo.

O acoplamento ao framework não desaparece — ele só é **isolado** num módulo
de "cola" separado (o starter), que fica fora do caminho de quem não usa Spring:

```java
// módulo separado: notification-spring-boot-starter
@Configuration
@ConditionalOnClass(NotificationService.class)
public class NotificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotificationService notificationService(EmailSender emailSender, SmsSender smsSender) {
        return new NotificationService(emailSender, smsSender);
    }
}
```

### ✅ Veredito

**Em bibliotecas e módulos reutilizáveis, injeção via construtor, sem anotação
de framework nenhuma na classe de domínio/serviço, é a posição padrão — sem
exceção.** `@Autowired`/`@Component` em campo só é aceitável em código de
**aplicação final** (o Spring Boot app que efetivamente será implantado), nunca
em algo que outro time vai importar como dependência. Framework é detalhe de
infraestrutura; a lógica que você quer reaproveitar não pode carregar esse
detalhe junto.

Se o construtor crescer demais (regra prática: 4+ parâmetros), o problema
geralmente não é "usar builder" — é a classe fazendo coisa demais. Extraia
colaboradores coesos antes de mascarar com um objeto de parâmetros.

---

## DI.2 — Injeção via construtor vs via setter

**❓ A decisão:** dependências obrigatórias devem entrar pelo construtor ou
podem ser setadas depois, via setter?

**📍 Contexto:** uma classe tem uma dependência sem a qual ela simplesmente
não funciona (`ReportFormatter`) e outra genuinamente opcional, com
comportamento padrão razoável (`MetricsRecorder`).

### 🔀 Opção A — Setter para tudo

```java
public class ReportGenerator {
    private ReportFormatter formatter;

    public void setFormatter(ReportFormatter formatter) {
        this.formatter = formatter;
    }

    public String generate(Report report) {
        return formatter.format(report); // NPE se o setter não foi chamado antes
    }
}
```

❌ O objeto existe, compila e é instanciável em um estado inválido
(`formatter == null`) — o erro só aparece quando `generate` é chamado, longe
de onde o bug foi introduzido.

### 🔀 Opção B — Construtor para o obrigatório, setter só para o opcional

```java
public class ReportGenerator {

    private final ReportFormatter formatter;         // obrigatório
    private MetricsRecorder metrics = MetricsRecorder.NOOP; // opcional, default seguro

    public ReportGenerator(ReportFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter é obrigatório");
    }

    public void setMetrics(MetricsRecorder metrics) {
        this.metrics = Objects.requireNonNull(metrics);
    }

    public String generate(Report report) {
        metrics.increment("report.generated");
        return formatter.format(report);
    }
}
```

✅ É **impossível** instanciar `ReportGenerator` sem um `ReportFormatter` válido
— o compilador barra na hora do `new`. O opcional continua flexível.

### ✅ Veredito

**Construtor para tudo que é obrigatório; setter (ou builder) só para o que é
genuinamente opcional e tem um default seguro.** Isso não é estilo — é fail
fast: um objeto que não pode existir em estado inválido elimina uma classe
inteira de bugs de "esqueci de configurar X".

A única exceção legítima para setter em dependência obrigatória é quebrar uma
**dependência circular** entre dois beans — e mesmo assim, trate isso como
dívida técnica sinalizada, não como solução definitiva. Circular dependency
quase sempre é sintoma de um design que deveria usar um evento ou uma
interface para inverter a direção do acoplamento.

---

## DI.3 — Múltiplas implementações de uma interface: `@Qualifier`/`@Primary` vs Strategy + registro

**❓ A decisão:** existem N implementações da mesma interface (`PaymentStrategy`
para PIX, cartão, boleto). Como o código que precisa escolher uma delas em
runtime deve ser estruturado?

**📍 Contexto:** a escolha depende de um dado de negócio (o método de
pagamento escolhido pelo cliente), não de um perfil de ambiente fixo.

### 🔀 Opção A — `if/else` ou `switch` com `@Qualifier` espalhado

```java
@Service
public class PaymentService {

    private final PaymentStrategy pixStrategy;
    private final PaymentStrategy cardStrategy;
    private final PaymentStrategy boletoStrategy;

    public PaymentService(
            @Qualifier("pix") PaymentStrategy pixStrategy,
            @Qualifier("card") PaymentStrategy cardStrategy,
            @Qualifier("boleto") PaymentStrategy boletoStrategy) {
        this.pixStrategy = pixStrategy;
        this.cardStrategy = cardStrategy;
        this.boletoStrategy = boletoStrategy;
    }

    public PaymentResult pay(PaymentRequest request) {
        return switch (request.method()) {
            case PIX -> pixStrategy.pay(request);
            case CARD -> cardStrategy.pay(request);
            case BOLETO -> boletoStrategy.pay(request);
        };
    }
}
```

❌ Toda nova forma de pagamento exige editar `PaymentService` (viola
Open/Closed) e adicionar mais um `@Qualifier` no construtor — cresce sem
limite e sem isolamento.

### 🔀 Opção B — Strategy com registro automático

```java
public interface PaymentStrategy {
    boolean supports(PaymentMethod method);
    PaymentResult pay(PaymentRequest request);
}

@Component
public class PixPaymentStrategy implements PaymentStrategy {
    public boolean supports(PaymentMethod method) { return method == PaymentMethod.PIX; }
    public PaymentResult pay(PaymentRequest request) { /* ... */ return null; }
}

@Service
public class PaymentService {

    private final List<PaymentStrategy> strategies;

    // Spring injeta automaticamente TODOS os beans do tipo PaymentStrategy
    public PaymentService(List<PaymentStrategy> strategies) {
        this.strategies = strategies;
    }

    public PaymentResult pay(PaymentRequest request) {
        return strategies.stream()
            .filter(s -> s.supports(request.method()))
            .findFirst()
            .orElseThrow(() -> new UnsupportedPaymentMethodException(request.method()))
            .pay(request);
    }
}
```

✅ Adicionar uma forma de pagamento nova = criar uma classe nova. `PaymentService`
nunca muda.

### ✅ Veredito

Se a escolha é **fixa em tempo de compilação/deploy** (ex.: qual implementação
usar por ambiente — `@Profile("test")` vs `@Profile("prod")`), `@Qualifier`/
`@Primary` é simples e suficiente, não complique. Se a escolha é **dirigida
por dado de negócio em runtime** entre 3 ou mais opções, use Strategy + lista
injetada — evita um `switch` que cresce a cada novo caso de negócio e é o
padrão que escala com Open/Closed Principle.

---

## DI.4 — Service Locator vs dependência explícita

**❓ A decisão:** uma dependência é necessária em um ponto profundo da pilha de
chamadas. Buscar via um localizador estático ou refatorar para passar
explicitamente?

### 🔀 Opção A — Service Locator estático

```java
public class OrderProcessor {
    public void process(Order order) {
        InventoryService inventory = ApplicationContextHolder.getBean(InventoryService.class);
        inventory.reserve(order);
    }
}
```

❌ A dependência de `OrderProcessor` em `InventoryService` fica invisível na
assinatura da classe — só aparece lendo o corpo do método.
❌ Testar `OrderProcessor` isoladamente exige mockar um contexto estático
global, não só passar um fake.
❌ Cria estado global oculto, dificultando análise de acoplamento e favorecendo
acoplamento oculto entre módulos que "deveriam" ser independentes.

### 🔀 Opção B — Dependência explícita, mesmo que exija refatorar a cadeia

```java
public class OrderProcessor {
    private final InventoryService inventory;

    public OrderProcessor(InventoryService inventory) {
        this.inventory = inventory;
    }

    public void process(Order order) {
        inventory.reserve(order);
    }
}
```

✅ A dependência é visível, testável com um fake simples, e o grafo de
acoplamento do sistema é auditável só lendo assinaturas de construtor.

### ✅ Veredito

**Evite Service Locator.** O "custo" de passar uma dependência por duas ou três
camadas extras é ínfimo comparado ao custo de dependências escondidas em um
código que precisa evoluir. Se a refatoração parecer grande demais, isso é
sinal de que a classe no topo da pilha provavelmente já faz coisa demais e
merece ser quebrada — não que o Service Locator é a saída fácil legítima.
Reserve o padrão, documentado como dívida técnica explícita, apenas para
infraestrutura cross-cutting genuinamente difícil de encaixar em DI comum
(ex.: código estático de bibliotecas legadas que você não controla).
