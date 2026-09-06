# 🚨 Tratamento de Erros

[← Voltar ao índice](../README.md)

---

## ERR.1 — Exceções *checked* vs *unchecked* em API pública

**❓ A decisão:** um método público de uma biblioteca pode falhar. A falha deve
ser uma exceção *checked* (`extends Exception`, obrigando o caller a tratar ou
declarar) ou *unchecked* (`extends RuntimeException`)?

**📍 Contexto:** você está desenhando a assinatura de `PdfRenderer.render(Document doc)`.

### 🔀 Opção A — Checked

```java
public String render(Document doc) throws RenderingException {
    // ...
}

// no caller, obrigatoriamente:
try {
    String pdf = renderer.render(doc);
} catch (RenderingException e) {
    // é forçado a lidar aqui, mesmo sem saber o que fazer
}
```

❌ Não compõe com interfaces funcionais (`Function<T,R>`, `Stream`) sem
embrulhar em `RuntimeException` de qualquer forma.
❌ Na prática, é comum degenerar em `catch (Exception e) { /* nada */ }` ou em
"declarar `throws Exception` e propagar", o que anula a suposta segurança do
compilador.
❌ Vaza detalhe de implementação para a assinatura — mudar a causa interna do
erro é uma mudança de contrato (breaking change).

### 🔀 Opção B — Unchecked, com hierarquia documentada

```java
public class RenderingException extends RuntimeException {
    public RenderingException(String message, Throwable cause) { super(message, cause); }
}

/**
 * @throws RenderingException se o documento não puder ser renderizado
 *         (formato inválido, fonte ausente, etc.)
 */
public String render(Document doc) {
    // ...
}
```

✅ Call sites simples continuam simples; quem quer tratar, trata explicitamente;
quem não trata, deixa propagar — igual a qualquer outro bug.
✅ Compõe naturalmente com lambdas e streams.

### ✅ Veredito

Siga a régua de Joshua Bloch (*Effective Java*, item 71): **use checked
exception só quando o caller pode, de forma realista, se recuperar
programaticamente** da condição — ex.: `InsufficientFundsException` numa
biblioteca de pagamentos, onde o caller genuinamente precisa ramificar
("ofereça outro cartão"). Para praticamente todo o resto — I/O, parsing,
falhas técnicas onde a única reação sensata é logar e abortar/retry — use
unchecked, com uma hierarquia clara de exceções e Javadoc `@throws` bem
escrito. Na dúvida, unchecked: é mais fácil transformar unchecked em checked
depois (raro) do que o contrário (breaking change certo).

---

## ERR.2 — `Optional<T>` vs `null` vs exceção para "ausência de valor"

**❓ A decisão:** um método de busca não encontra o que procurava. Retorna
`null`, `Optional<T>`, ou lança uma exceção?

### 🔀 Opção A — `null`

```java
public User findByEmail(String email) {
    return repository.query(email); // pode retornar null
}

// caller precisa lembrar de checar — e frequentemente esquece
User user = service.findByEmail(email);
user.getName(); // NPE se o e-mail não existir
```

❌ Nada no tipo do retorno avisa o caller que o valor pode estar ausente — a
única defesa é disciplina e documentação, que se perdem com o tempo.

### 🔀 Opção B — `Optional<T>`

```java
public Optional<User> findByEmail(String email) {
    return Optional.ofNullable(repository.query(email));
}

// o compilador força a lidar com a ausência
String name = service.findByEmail(email)
    .map(User::getName)
    .orElse("desconhecido");
```

✅ O tipo comunica explicitamente "isso pode não existir" — o caller decide o
que fazer (`orElse`, `orElseThrow`, `ifPresent`), mas não pode ignorar
silenciosamente.

### 🔀 Opção C — Exceção

```java
public User getByIdOrThrow(Long id) {
    return repository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException(User.class, id));
}
```

✅ Correto quando a ausência **viola uma pré-condição** já assumida naquele
ponto do fluxo (ex.: você já validou que o usuário existe uma camada acima) —
a exceção aborta o caso de uso de forma limpa em vez de espalhar `null`-checks
por todo canto.

### ✅ Veredito

**Nunca retorne `null` de uma API pública** — é a maior fonte isolada de
`NullPointerException` em qualquer base Java. Regra prática pelo nome do
método: `find...` retorna `Optional<T>` (ausência é resultado normal e
esperado); `get...`/`require...` lança exceção (ausência é violação de
invariante ali). `Optional` nunca deve ser usado como tipo de campo, parâmetro
de construtor ou dentro de coleções — seu único lugar correto é tipo de
retorno.

---

## ERR.3 — Result/Either vs exceção para fluxo de erro esperado (validação)

**❓ A decisão:** uma validação de formulário precisa reportar **todas** as
violações de uma vez, não só a primeira. Isso deve ser feito lançando exceção
no primeiro erro, ou acumulando resultados num objeto de retorno?

### 🔀 Opção A — Exceção no primeiro erro (fail-fast)

```java
public void validate(OrderRequest request) {
    if (request.items().isEmpty()) {
        throw new ValidationException("O pedido precisa ter ao menos um item");
    }
    if (request.customerId() == null) {
        throw new ValidationException("Cliente é obrigatório");
    }
    // o segundo erro nunca é visto se o primeiro já lançou
}
```

❌ O usuário corrige um campo, reenvia, descobre o próximo erro — um de cada
vez. Péssima experiência quando existem múltiplos campos inválidos.
❌ Usar exceção para controle de fluxo esperado (não excepcional) tem custo de
captura de stack trace, desnecessário quando o "erro" é uma ocorrência comum
do domínio (entrada inválida do usuário, não uma falha do sistema).

### 🔀 Opção B — Objeto de resultado que acumula violações

```java
public record ValidationResult(List<String> errors) {
    public boolean isValid() { return errors.isEmpty(); }
}

public ValidationResult validate(OrderRequest request) {
    List<String> errors = new ArrayList<>();
    if (request.items().isEmpty()) {
        errors.add("O pedido precisa ter ao menos um item");
    }
    if (request.customerId() == null) {
        errors.add("Cliente é obrigatório");
    }
    return new ValidationResult(errors);
}

// caller decide o que fazer com a lista completa
ValidationResult result = validate(request);
if (!result.isValid()) {
    return ResponseEntity.badRequest().body(result.errors());
}
```

✅ Reporta tudo de uma vez; não usa exceção para um caminho de negócio normal.

### ✅ Veredito

Para validação **voltada ao usuário final**, onde reportar todas as violações
de uma vez é parte do produto, use um objeto de resultado que acumula erros —
não exceção. Para checagem de **invariante interna** onde a primeira violação
já é motivo suficiente para abortar (ex.: pré-condição de um método privado),
fail-fast com exceção continua correto e mais simples.

Não introduza um `Either<L, R>` genérico espalhado pelo código só para "evitar
exceções" — Java não tem suporte nativo confortável a esse idioma (lambdas
checked continuam dolorosas) e isso adiciona uma camada de indireção que o
time inteiro precisa aprender. Use uma biblioteca como Vavr, ou um record
simples como acima, de forma **pontual**, onde acumular resultado é
genuinamente valioso — não como padrão obrigatório em todo o código.

---

## ERR.4 — Propagar vs empacotar (`wrap`) exceções entre camadas

**❓ A decisão:** a camada de persistência lança `DataAccessException`
(Spring)/`SQLException`. A camada de serviço deve deixar propagar como está,
ou capturar e relançar como uma exceção de domínio?

### 🔀 Opção A — Propagação direta

```java
public Order save(Order order) {
    return orderRepository.save(order); // DataAccessException vaza pra cima sem filtro
}
```

❌ A camada de aplicação (ex.: um controller REST) agora conhece detalhes de
Hibernate/JDBC. Trocar o provedor de persistência vira uma mudança que quebra
contratos em camadas que não deveriam nem saber que JPA existe.

### 🔀 Opção B — Empacotar na fronteira, preservando a causa

```java
public Order save(Order order) {
    try {
        return orderRepository.save(order);
    } catch (DataAccessException e) {
        throw new OrderPersistenceException("Falha ao salvar pedido " + order.getId(), e);
    }
}
```

✅ A camada de cima só conhece `OrderPersistenceException` — um conceito de
domínio, não de infraestrutura. A causa raiz (`e`) fica preservada para
debugging via `getCause()`/stack trace completo.

### ✅ Veredito

**Empacote exceções sempre que a chamada cruza uma fronteira arquitetural**
(persistência → domínio, cliente HTTP externo → aplicação), traduzindo uma
falha técnica para um conceito do seu domínio. **Sempre preserve a causa
original** — nunca capture e relance sem passar a exceção original como
`cause`; isso destrói a stack trace real e transforma qualquer investigação de
incidente numa adivinhação. Não empacote indiscriminadamente dentro da mesma
camada/módulo só por hábito — isso só adiciona ruído sem ganho de
encapsulamento.
