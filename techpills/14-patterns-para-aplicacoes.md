# 🧩 Patterns Importantes para Aplicações

[← Voltar ao índice](../README.md)

---

## APP.1 — Strategy vs `if`/`switch` encadeado para variações de regra de negócio

**❓ A decisão:** um comportamento varia conforme um tipo/categoria (cálculo
de frete por transportadora, desconto por tipo de cliente, taxa por meio de
pagamento). Você concentra as variações num `switch`/`if-else` crescente, ou
extrai cada uma para uma implementação de uma interface comum, escolhida em
runtime?

### 🔀 Antipadrão — um `switch` que cresce a cada nova variação

```java
public class CalculadoraFrete {
    public BigDecimal calcular(String transportadora, Pedido pedido) {
        switch (transportadora) {
            case "SEDEX": return calcularSedex(pedido);
            case "PAC": return calcularPac(pedido);
            case "TRANSPORTADORA_X": return calcularTransportadoraX(pedido);
            // toda nova transportadora exige editar esta classe
            default: throw new IllegalArgumentException("Transportadora desconhecida");
        }
    }
}
```

❌ A classe nunca para de crescer, mistura lógicas completamente diferentes
no mesmo arquivo, e testar a regra de uma transportadora exige entender (ou
pelo menos compilar) as regras de todas as outras.

### 🔀 Correto — cada variação isolada atrás de uma interface (Strategy)

```java
public interface FreteCalculator {
    boolean suporta(String transportadora);
    BigDecimal calcular(Pedido pedido);
}

@Component
public class SedexFreteCalculator implements FreteCalculator {
    public boolean suporta(String transportadora) { return "SEDEX".equals(transportadora); }
    public BigDecimal calcular(Pedido pedido) { /* regra isolada, testável sozinha */ }
}

@Service
public class CalculadoraFrete {
    private final List<FreteCalculator> calculators; // injetados pelo Spring

    public BigDecimal calcular(String transportadora, Pedido pedido) {
        return calculators.stream()
            .filter(c -> c.suporta(transportadora))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Transportadora desconhecida"))
            .calcular(pedido);
    }
}
```

Uma nova transportadora vira uma nova classe — zero mudança no código
existente (Open/Closed), e cada regra é testável isoladamente.

### ✅ Veredito

Quando o número de variações tende a crescer e cada uma carrega lógica não
trivial própria, Strategy é o padrão certo: isola cada regra, permite testar
cada uma isoladamente e evita que o "calculador" vire um arquivo que ninguém
quer tocar. Para 2–3 variações triviais e estáveis (baixíssima chance de
crescer), um `switch` simples ainda é mais legível — não introduza a
abstração antes de ela ser necessária.

---

## APP.2 — Chain of Responsibility para pipelines vs método monolítico com validações em sequência

**❓ A decisão:** um fluxo (validação de pedido, aprovação de crédito) tem
múltiplos passos conceitualmente independentes, que podem crescer ou ser
reordenados. Você escreve um método longo com `if`s sequenciais, ou modela
como uma cadeia de handlers?

### 🔀 Antipadrão — um método com validações sequenciais amarradas

```java
public void validar(Pedido pedido) {
    if (pedido.getItens().isEmpty()) throw new PedidoInvalidoException("sem itens");
    if (pedido.getCliente() == null) throw new PedidoInvalidoException("sem cliente");
    if (pedido.getEndereco() == null) throw new PedidoInvalidoException("sem endereço");
    // cada nova regra = editar este método e arriscar mudar a ordem/lógica existente
}
```

❌ Impossível reordenar, remover ou reaproveitar um subconjunto dessas
validações em outro fluxo (ex.: um fluxo de "pedido rascunho" que só precisa
de parte delas) sem duplicar ou desmontar o método inteiro.

### 🔀 Correto — cadeia de handlers independentes e ordenável

```java
public interface PedidoValidator {
    void validar(Pedido pedido); // lança exceção específica se reprovar
}

@Component
@Order(1)
public class ItensPresentesValidator implements PedidoValidator { /* ... */ }

@Component
@Order(2)
public class EnderecoValidoValidator implements PedidoValidator { /* ... */ }

@Service
public class PedidoValidationService {
    private final List<PedidoValidator> validators; // Spring injeta já ordenados por @Order

    public void validar(Pedido pedido) {
        validators.forEach(v -> v.validar(pedido));
    }
}
```

O mesmo princípio de uma cadeia de `Filter`s do Servlet/Spring Security:
cada elo resolve uma responsabilidade e decide se a cadeia continua.

### ✅ Veredito

Chain of Responsibility (ou o "pipeline" equivalente com uma lista ordenada
de handlers) vale a pena quando os passos são conceitualmente
independentes, crescem com frequência, ou são reaproveitados em mais de um
fluxo. Para 2–3 validações fixas que sempre andam juntas e nunca mudam de
ordem, um método direto com `if`s é mais fácil de ler — não fragmente
prematuramente algo que sempre foi, e sempre será, uma sequência fixa.

---

## APP.3 — Domain Events (Observer) vs acoplamento direto entre casos de uso

**❓ A decisão:** uma ação de negócio ("pedido confirmado") precisa disparar
efeitos colaterais em outros módulos (enviar e-mail, atualizar estoque,
notificar parceiro). O caso de uso que confirma o pedido deve chamar
diretamente cada um desses serviços, ou publicar um evento que os
interessados escutam?

### 🔀 Antipadrão — o caso de uso conhece todos os efeitos colaterais

```java
@Service
public class ConfirmarPedidoService {
    private final EmailService email;
    private final EstoqueService estoque;
    private final NotificacaoParceiroService parceiro; // cresce a cada novo efeito

    public void confirmar(Pedido pedido) {
        pedido.confirmar();
        repository.save(pedido);
        email.enviarConfirmacao(pedido);        // se isso falhar, derruba a confirmação?
        estoque.reservar(pedido);
        parceiro.notificar(pedido);
    }
}
```

❌ Todo novo efeito colateral exige editar este serviço central, que precisa
conhecer módulos completamente alheios à sua responsabilidade real — e uma
falha num efeito secundário (notificar parceiro) pode acabar impedindo a
própria confirmação do pedido, a menos que cada chamada seja envolta em
tratamento defensivo.

### 🔀 Correto — o caso de uso publica um evento, interessados reagem sozinhos

```java
@Service
public class ConfirmarPedidoService {
    private final ApplicationEventPublisher events;

    public void confirmar(Pedido pedido) {
        pedido.confirmar();
        repository.save(pedido);
        events.publishEvent(new PedidoConfirmadoEvent(pedido.getId()));
    }
}

@Component
public class EnviarEmailConfirmacaoListener {
    @TransactionalEventListener(phase = AFTER_COMMIT) // só reage se a transação principal COMMITAR
    public void aoConfirmar(PedidoConfirmadoEvent evento) { /* ... */ }
}
```

Cada módulo interessado evolui, é testado e falha isoladamente, sem que o
caso de uso principal precise saber que ele existe.

### ✅ Veredito

Use eventos de domínio quando o efeito colateral é conceitualmente
independente do caso de uso principal (o pedido não deixa de estar
confirmado se o e-mail falhar) e quando a lista de interessados tende a
crescer. Chamada direta ainda é apropriada quando o efeito é parte do
próprio significado da operação (debitar saldo é parte de "efetuar
pagamento", não um efeito colateral) — não transforme em evento uma etapa
que é, na verdade, obrigatória e sequencial dentro da própria regra de
negócio. Para consistência entre serviços diferentes (não só listeners
in-process), veja o padrão Outbox em [TRX.2](11-transacoes-e-consistencia.md#trx2--transação-distribuída-2pc-vs-padrão-outboxsaga).

---

## APP.4 — Application Service (Facade) vs lógica de negócio dentro do Controller

**❓ A decisão:** onde vive a orquestração de um caso de uso — validações,
chamadas a múltiplos repositórios/serviços, mapeamento — dentro do próprio
`@RestController`, ou numa camada de aplicação dedicada?

### 🔀 Antipadrão — controller gordo fazendo orquestração de negócio

```java
@RestController
public class PedidoController {
    @Autowired private PedidoRepository pedidos;
    @Autowired private EstoqueRepository estoque;
    @Autowired private ApplicationEventPublisher events;

    @PostMapping("/pedidos/{id}/confirmar")
    public ResponseEntity<?> confirmar(@PathVariable Long id) {
        Pedido pedido = pedidos.findById(id).orElseThrow();
        if (!estoque.temDisponivel(pedido)) return ResponseEntity.badRequest().build();
        pedido.confirmar();
        pedidos.save(pedido);
        events.publishEvent(new PedidoConfirmadoEvent(id));
        return ResponseEntity.ok().build();
        // essa regra só pode ser acionada via HTTP — um job agendado ou um
        // consumidor de fila que precise do mesmo fluxo tem que duplicá-lo
    }
}
```

### 🔀 Correto — controller como adaptador fino, orquestração numa camada de aplicação

```java
@Service
public class ConfirmarPedidoUseCase { // sem NENHUMA dependência de Spring Web
    public ConfirmarPedidoUseCase(PedidoRepository pedidos, EstoqueRepository estoque,
                                   ApplicationEventPublisher events) { /* ... */ }

    public void executar(Long pedidoId) {
        Pedido pedido = pedidos.findById(pedidoId).orElseThrow(PedidoNaoEncontradoException::new);
        if (!estoque.temDisponivel(pedido)) throw new EstoqueInsuficienteException();
        pedido.confirmar();
        pedidos.save(pedido);
        events.publishEvent(new PedidoConfirmadoEvent(pedidoId));
    }
}

@RestController
public class PedidoController {
    private final ConfirmarPedidoUseCase useCase;

    @PostMapping("/pedidos/{id}/confirmar")
    public ResponseEntity<?> confirmar(@PathVariable Long id) {
        useCase.executar(id); // controller só traduz HTTP <-> aplicação
        return ResponseEntity.ok().build();
    }
}
```

Agora o mesmo `ConfirmarPedidoUseCase` pode ser chamado por um consumidor de
fila, um job agendado, um comando de CLI ou um teste unitário puro — sem
subir contexto web nenhum.

### ✅ Veredito

Mantenha a orquestração de caso de uso numa camada de aplicação livre de
framework web (o mesmo espírito de [ARC.2](06-arquitetura-e-fronteiras.md#arc2--domínio-livre-de-framework-vs-domínio-anêmico-acoplado-a-springjpa)
— domínio livre de framework). O controller é só um adaptador de protocolo:
faz parsing de request, delega, mapeia resultado para response. Isso paga
dividendos assim que o mesmo caso de uso precisa ser acionado por mais de um
gatilho, e torna o teste da regra de negócio independente de infraestrutura
HTTP.
