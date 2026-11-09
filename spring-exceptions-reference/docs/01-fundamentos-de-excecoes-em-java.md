# 01 — Fundamentos de exceções em Java

[← Índice](README.md) · [Próximo: Desenhando as exceções da aplicação →](02-desenhando-excecoes-da-aplicacao.md)

---

Antes de Spring, HTTP ou error codes, é preciso dominar a mecânica da linguagem. Quase todo
bug de "erro sumiu", "transação não deu rollback" ou "stack trace inútil" nasce de um detalhe deste capítulo.

## 1.1 A hierarquia

```
Throwable
├── Error                      ← problema da JVM/ambiente. NÃO capture.
│   ├── OutOfMemoryError
│   ├── StackOverflowError
│   └── LinkageError ...
└── Exception                  ← checked (o compilador obriga a tratar ou declarar)
    ├── IOException
    ├── SQLException
    ├── InterruptedException
    └── RuntimeException       ← unchecked (o compilador não obriga)
        ├── IllegalArgumentException
        ├── IllegalStateException
        ├── NullPointerException
        ├── UnsupportedOperationException
        └── (as exceções da sua aplicação, quase sempre)
```

| Tipo | Significado | Quem deve tratar |
|---|---|---|
| `Error` | A JVM está em apuros (memória, pilha, classe ausente). | Ninguém. Deixe derrubar; o orquestrador reinicia. |
| Checked (`Exception`) | Falha **recuperável e esperada** que o caller *pode* tratar (arquivo não existe, rede caiu). | O caller imediato, ou é traduzida. |
| Unchecked (`RuntimeException`) | Violação de contrato/bug **ou** falha esperada que o caller normalmente não sabe tratar. | Quem souber o que fazer; senão, o handler global. |

> **Regra prática:** nunca escreva `catch (Throwable t)` nem `catch (Error e)` em código de
> aplicação. A exceção (rara) é infraestrutura de baixo nível, como um executor que precisa
> registrar a falha de uma tarefa antes de morrer.

## 1.2 Checked vs unchecked: a posição moderna

A régua clássica (Joshua Bloch, *Effective Java*, itens 70 e 71):

- **Checked** para condições das quais o caller **consegue se recuperar** de forma razoável.
- **Unchecked** para erros de programação e para tudo em que o tratamento obrigatório não agrega.

Na prática, o ecossistema Java moderno (Spring, JPA, Jackson 3, AWS SDK v2) convergiu para
**unchecked**, por três motivos:

1. **Não compõe com lambdas.** `Function<T,R>` não declara `throws`. Toda checked dentro de um
   `stream().map(...)` vira um embrulho em `RuntimeException`.
2. **Vira ritual.** O tratamento forçado degenera em `catch (Exception e) {}` ou `throws Exception`
   na assinatura, o que anula o benefício.
3. **Vaza implementação.** `throws SQLException` numa interface de repositório amarra o contrato ao JDBC.

E um motivo **específico de Spring**, que costuma decidir a discussão:

> `@Transactional` faz rollback por padrão **apenas** em `RuntimeException` e `Error`.
> Uma checked exception lançada de um método transacional **faz commit**, a menos que você
> declare `rollbackFor`. (Ver [capítulo 08](08-integracoes-transacoes-e-async.md#82-transações-e-rollback).)

**Neste projeto:** todas as exceções da aplicação são unchecked
([`ApplicationException`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ApplicationException.java)).
A única checked aparece onde ela é realista: no "SDK de terceiro"
([`PaymentSdkException`](../src/main/java/dev/cheatsheet/exceptions/payment/sdk/PaymentSdkException.java)),
e é traduzida na fronteira.

## 1.3 Prefira as exceções padrão do JDK

Antes de criar uma exceção, veja se uma padrão já diz o que você quer (*Effective Java*, item 72):

| Exceção | Use quando |
|---|---|
| `IllegalArgumentException` | Um argumento tem valor inválido (`quantity = -1`). |
| `IllegalStateException` | O objeto não está num estado que permita a operação. |
| `NullPointerException` | Argumento nulo onde não pode (`Objects.requireNonNull(x, "x")`). |
| `UnsupportedOperationException` | A operação não é suportada (coleção imutável, método opcional). |
| `IndexOutOfBoundsException` | Índice fora da faixa. |
| `ConcurrentModificationException` | Modificação concorrente detectada. |

Essas exceções significam **"alguém programou errado"**. Por isso, quando chegam ao handler
global do projeto, viram **500**, e não 400: a borda deveria ter barrado a entrada inválida antes
(ver [`OrderItem`](../src/main/java/dev/cheatsheet/exceptions/order/OrderItem.java) e o
[capítulo 07](07-validacao.md)).

## 1.4 try / catch / finally: armadilhas

### `finally` com `return` engole a exceção

```java
int parse() {
    try {
        throw new IllegalStateException("perdida para sempre");
    } finally {
        return 0;   // ❌ a exceção some sem deixar rastro
    }
}
```

Nunca use `return`, `break` ou `throw` dentro de `finally`.

### try-with-resources e exceções suprimidas

```java
try (var in = Files.newInputStream(path);
     var out = Files.newOutputStream(target)) {
    in.transferTo(out);
}
```

Se o corpo lança uma exceção **e** o `close()` também, a do corpo é a principal e a do `close()`
fica em `e.getSuppressed()`. Com `try/finally` manual, a do `close()` **substituiria** a original:
você veria "erro ao fechar" e nunca a causa real. Sempre prefira try-with-resources para `AutoCloseable`.

### Multi-catch e rethrow preciso

```java
try {
    sdk.charge(token, amount);
} catch (SocketTimeoutException | ConnectException e) {
    throw new IntegrationException(PAYMENT_PROVIDER_TIMEOUT, e);
}
```

## 1.5 Encadeamento de causa: a regra que você nunca pode quebrar

```java
// ❌ perde a stack trace original: o log mostra onde você embrulhou, não onde quebrou
catch (SQLException e) {
    throw new RepositoryException("Falha ao salvar pedido");
}

// ❌ pior: converte em string e perde tipo, causa e stack
catch (SQLException e) {
    throw new RepositoryException("Falha: " + e.getMessage());
}

// ✅ preserva a cadeia: o log mostra "Caused by: java.sql.SQLException ..."
catch (SQLException e) {
    throw new RepositoryException("Falha ao salvar pedido " + order.id(), e);
}
```

No projeto, [`IntegrationException`](../src/main/java/dev/cheatsheet/exceptions/shared/error/IntegrationException.java)
**exige** a causa no construtor (`Objects.requireNonNull(cause)`): é impossível traduzir uma
falha de integração perdendo a original.

## 1.6 `InterruptedException`: restaure a flag

```java
try {
    queue.take();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // ✅ devolve o sinal de interrupção
    throw new IllegalStateException("Interrompido aguardando a fila", e);
}
```

Engolir `InterruptedException` sem restaurar a flag impede que o executor/shutdown funcione:
a thread "esquece" que pediram para ela parar.

## 1.7 Custo de performance

- O caro **não** é o `throw`; é o `fillInStackTrace()`, que percorre a pilha na construção.
- Usar exceção como **controle de fluxo** em caminho quente (ex.: `try { Integer.parseInt } catch`
  para checar se é número em um loop de milhões) é lento **e** ilegível.
- Para exceções de altíssimo volume e conteúdo conhecido, existe o construtor
  `RuntimeException(String, Throwable, enableSuppression, writableStackTrace)` com
  `writableStackTrace = false`. Use com parcimônia: sem stack trace, depurar fica muito mais difícil.
- **`-XX:-OmitStackTraceInFastThrow`:** a HotSpot, depois de muitas `NullPointerException` no mesmo
  ponto, passa a lançar uma instância pré-alocada **sem stack trace**. Se você vê NPEs "sem linha"
  no log de produção, é isso. Ligue essa flag no serviço que está investigando.

## 1.8 Mensagens úteis de NPE (Java 14+)

Desde o JEP 358, a NPE diz **o que** era nulo:

```
Cannot invoke "String.length()" because the return value of "Customer.name()" is null
```

Isso torna `Objects.requireNonNull(x, "x")` ainda útil (falha mais cedo, no ponto certo),
mas menos crítico para diagnóstico.

## 1.9 Exceções fora da thread principal

| Onde | O que acontece com a exceção |
|---|---|
| `new Thread(...)` | Vai para o `UncaughtExceptionHandler` (por padrão: imprime no stderr e a thread morre). |
| `ExecutorService.submit` | Fica guardada no `Future`; aparece embrulhada em `ExecutionException` no `get()`. **Se ninguém chama `get()`, ela some.** |
| `ExecutorService.execute` | Vai para o `UncaughtExceptionHandler` da thread do pool. |
| `CompletableFuture` | Completa o future excepcionalmente; nos estágios seguintes chega como `CompletionException`. Use `exceptionally`/`handle`/`whenComplete`. |

```java
CompletableFuture.supplyAsync(this::fetch)
        .exceptionally(ex -> {
            Throwable cause = ex instanceof CompletionException ce ? ce.getCause() : ex;
            log.warn("Falha ao buscar", cause);
            return fallback();
        });
```

Spring `@Async` e `@Scheduled` têm suas próprias regras: ver [capítulo 08](08-integracoes-transacoes-e-async.md#83-async-scheduled-e-mensageria).

---

### ✅ Resumo do capítulo

- `Error` não se captura; `Throwable` muito menos.
- Exceções da aplicação: **unchecked**. Checked só onde o caller realmente consegue se recuperar.
- Prefira exceções padrão do JDK para erro de programação.
- Nunca perca a causa: `new X(msg, cause)`.
- try-with-resources sempre; nada de `return` em `finally`.
- `InterruptedException`: restaure a flag.
- Exceção não é `goto`: não use para fluxo normal em caminho quente.
