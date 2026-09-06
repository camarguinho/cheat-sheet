# ⚡ Concorrência

[← Voltar ao índice](../README.md)

---

## CON.1 — `synchronized` vs `java.util.concurrent` (`ConcurrentHashMap`, `Atomic*`, `Lock`)

**❓ A decisão:** um estado compartilhado precisa de acesso seguro entre
threads. `synchronized` ou uma estrutura de `java.util.concurrent`?

### 🔀 Opção A — `synchronized` na mão, reimplementando o que já existe pronto

```java
public class Cache {
    private final Map<String, Object> map = new HashMap<>();

    public synchronized Object get(String key) { return map.get(key); }
    public synchronized void put(String key, Object value) { map.put(key, value); }
}
```

❌ Serializa **todo** acesso ao mapa (leitura e escrita) atrás de um único
lock — mata paralelismo desnecessariamente quando uma estrutura concorrente
já resolveria isso com granularidade muito melhor.

```java
// Pior ainda: operação composta com lock manual, fácil de fazer errado
public synchronized void incrementIfAbsent(String key) {
    if (!map.containsKey(key)) {
        map.put(key, 1);
    } else {
        map.put(key, (int) map.get(key) + 1);
    }
}
```

### 🔀 Opção B — Estrutura de `java.util.concurrent` correta pro caso

```java
public class Cache {
    private final ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();

    public void incrementIfAbsent(String key) {
        map.merge(key, 1, Integer::sum); // atômico, sem lock explícito no caller
    }
}

// contador simples: AtomicLong em vez de synchronized
private final AtomicLong requestCount = new AtomicLong();
public void recordRequest() { requestCount.incrementAndGet(); }
```

✅ `ConcurrentHashMap.merge`/`computeIfAbsent` fazem a operação composta
atomicamente, sem lock explícito e com granularidade por bucket, não global.

### ✅ Veredito

**Prefira sempre a estrutura pronta de `java.util.concurrent`** ao estado
compartilhado que ela já resolve — `ConcurrentHashMap` para mapas (usando
`compute`/`merge`/`computeIfAbsent` para operações compostas, nunca
`get`+`put` separados), `AtomicLong`/`AtomicReference` para uma única
variável. Reserve `synchronized` para seções críticas pequenas, de baixa
contenção, sem equivalente pronto na biblioteca padrão — nesse caso ele é
mais simples de ler que um `ReentrantLock` explícito. Use `Lock` explícito só
quando precisar de recurso que `synchronized` não tem (`tryLock` com timeout,
aquisição interrompível, múltiplas `Condition`). Nunca misture duas
estratégias de sincronização (`synchronized` + `Atomic`, por exemplo) sobre o
mesmo estado — escolha uma e documente.

---

## CON.2 — Singleton *stateless* vs estado mutável por instância

**❓ A decisão:** um `@Service` do Spring pode ter campos de instância
mutáveis?

**📍 Contexto:** por padrão, um bean Spring é singleton — uma única instância
atende **todas** as requisições, de **todas** as threads, simultaneamente.

### 🔀 Antipadrão — estado mutável num bean singleton

```java
@Service
public class ReportService {
    private List<String> processedIds = new ArrayList<>(); // compartilhado entre TODAS as requisições!

    public void process(String id) {
        processedIds.add(id); // ArrayList não é thread-safe: corrupção silenciosa sob carga
    }
}
```

❌ `processedIds` é compartilhado por todas as threads que chamam `process`
concorrentemente — `ArrayList` não é thread-safe, então isso corrompe
silenciosamente sob carga (bug intermitente, difícil de reproduzir, típico de
aparecer só em produção sob tráfego real).

### 🔀 Correto — bean singleton stateless

```java
@Service
public class ReportService {
    private final ReportRepository repository; // colaborador imutável, ok

    public ReportService(ReportRepository repository) {
        this.repository = repository;
    }

    public void process(String id) {
        repository.markProcessed(id); // estado vive no banco, não na instância do bean
    }
}
```

✅ Nenhum campo de instância muda depois da construção — a mesma instância
pode atender chamadas concorrentes sem nenhuma condição de corrida.

### ✅ Veredito

**Um bean Spring padrão (singleton) precisa ser stateless**: campos de
instância só para colaboradores imutáveis injetados, nunca para dado que
muda por chamada/request. Se você genuinamente precisa de estado por
requisição, use um parâmetro/retorno de método, um bean `@RequestScope`, ou
armazene o estado num lugar explicitamente pensado para isso (banco, cache
distribuído) — nunca num campo mutável de um singleton "pra lembrar de algo
entre chamadas".

---

## CON.3 — `CompletableFuture` (assíncrono) vs chamada bloqueante para bibliotecas

**❓ A decisão:** uma biblioteca faz uma chamada de I/O (rede, disco). Ela deve
expor uma API síncrona (bloqueante) ou assíncrona (`CompletableFuture`)?

### 🔀 Opção A — Só bloqueante

```java
public String fetchUser(String id) {
    return httpClient.send(request(id), HttpResponse.BodyHandlers.ofString()).body();
}
```

⚠️ Simples de usar, mas **impossível de "desbloquear" de fora**: um
consumidor que roda num pipeline reativo (Reactor/RxJava) fica forçado a
rodar essa chamada numa thread pool separada (`Schedulers.boundedElastic()`)
só para não travar o event loop.

### 🔀 Opção B — Núcleo assíncrono, com conveniência síncrona por cima

```java
public CompletableFuture<String> fetchUserAsync(String id) {
    return httpClient.sendAsync(request(id), HttpResponse.BodyHandlers.ofString())
        .thenApply(HttpResponse::body);
}

// conveniência para quem só quer chamar de forma simples
public String fetchUser(String id) {
    return fetchUserAsync(id).join();
}
```

✅ Quem quer compor de forma não bloqueante, compõe (`thenApply`,
`thenCompose`, `allOf`); quem só quer o valor, chama a versão síncrona. A
biblioteca não impõe a escolha.

### ✅ Veredito

Para uma **biblioteca/SDK de baixo nível** que faz I/O, exponha o núcleo
assíncrono (`CompletableFuture`) e ofereça um wrapper bloqueante como
conveniência — nunca o contrário, porque uma API só-bloqueante não pode ser
"des-bloqueada" de fora. **Ressalva pós-Java 21:** com *virtual threads*, uma
chamada bloqueante bem escrita em **código de aplicação** (não uma biblioteca
compartilhada amplamente) frequentemente já é suficiente na prática — o custo
de thread-por-chamada que justificava APIs assíncronas perde grande parte da
relevância. Reserve `CompletableFuture`/reativo para necessidade real de
composição/backpressure, não como escolha padrão automática em todo código
novo.

---

## CON.4 — `ThreadLocal`: quando usar e o risco em pools de threads

**❓ A decisão:** você precisa propagar um dado (trace ID, usuário atual) por
várias camadas sem passá-lo explicitamente como parâmetro em toda assinatura.
`ThreadLocal` é seguro?

### 🔀 Uso correto, com limpeza garantida

```java
public class TraceContext {
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    public static void set(String traceId) { TRACE_ID.set(traceId); }
    public static String get() { return TRACE_ID.get(); }
    public static void clear() { TRACE_ID.remove(); }
}

// num único filtro/interceptor centralizado — não espalhado pelo código
public class TraceIdFilter implements Filter {
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        try {
            TraceContext.set(UUID.randomUUID().toString());
            chain.doFilter(req, res);
        } finally {
            TraceContext.clear(); // OBRIGATÓRIO: sem isso, vaza pra próxima requisição
        }
    }
}
```

### 🔀 O bug clássico — esquecer o `remove()`

```java
public void handle(Request req) {
    TraceContext.set(req.getTraceId());
    process(req);
    // sem clear()! numa thread pool, a MESMA thread atende a próxima requisição
    // e vai carregar o traceId da requisição ANTERIOR — vazamento de contexto
    // entre requisições não relacionadas.
}
```

❌ Em ambientes de thread pool (containers servlet, `ExecutorService`), a
thread é **reaproveitada**. Sem `remove()` num `finally`, o valor "vaza" para
a próxima tarefa que reusar aquela thread — de trace ID incorreto a, em casos
piores, dados de um usuário aparecendo no contexto de outro.

### ✅ Veredito

`ThreadLocal` é apropriado para propagar contexto que seria doloroso passar
explicitamente por muitas camadas dentro da **mesma** execução — mas **todo**
`set()` precisa de um `remove()` correspondente num `finally`, idealmente
centralizado num único filtro/interceptor, nunca espalhado. Em Java 21+,
para dado imutável propagado para threads filhas (concorrência estruturada),
prefira `ScopedValue` (JEP 446) a `ThreadLocal`: é imutável, tem escopo
automaticamente limitado e não exige limpeza manual — elimina exatamente a
classe de bug acima por construção.
