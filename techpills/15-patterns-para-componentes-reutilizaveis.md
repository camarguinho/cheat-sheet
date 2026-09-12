# 📦 Patterns Importantes para Componentes Reutilizáveis

[← Voltar ao índice](../README.md)

---

## LIB.1 — Extensibilidade: Template Method (herança) vs Strategy/SPI (composição)

**❓ A decisão:** sua biblioteca precisa permitir que o consumidor customize
um trecho de comportamento (como serializar, como resolver uma credencial).
Você expõe uma classe abstrata com métodos `protected` para sobrescrever
(Template Method), ou uma interface pequena que o consumidor implementa e
registra (Strategy/SPI)?

### 🔀 Antipadrão — extensão via herança de uma classe da biblioteca

```java
public abstract class AbstractHttpClient {
    public final Response send(Request request) {
        Request preparado = beforeSend(request); // consumidor customiza aqui
        return doSend(preparado);
    }

    protected abstract Request beforeSend(Request request);
}
// consumidor: public class MeuHttpClient extends AbstractHttpClient { ... }
```

❌ O consumidor fica preso a essa hierarquia (herança única em Java — não
pode estender mais nada), não consegue compor múltiplas customizações, e
qualquer mudança futura num método concreto da classe base pode quebrar
silenciosamente subclasses que dependiam do comportamento antigo (fragile
base class) — a implementação interna da biblioteca vira, na prática, parte
do contrato público.

### 🔀 Correto — extensão via interface pequena, injetada por composição

```java
public interface RequestInterceptor {
    Request intercept(Request request);
}

public class HttpClient {
    private final List<RequestInterceptor> interceptors;

    public HttpClient(List<RequestInterceptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
    }

    public Response send(Request request) {
        Request preparado = request;
        for (RequestInterceptor i : interceptors) preparado = i.intercept(preparado);
        return doSend(preparado);
    }
}
// consumidor: implementa RequestInterceptor e registra quantos quiser, sem herdar nada
```

### ✅ Veredito

Em bibliotecas, prefira extensão via composição (Strategy/SPI com interface
pequena) a extensão via herança (Template Method com classe abstrata
exposta ao consumidor) — herança expõe implementação interna como parte do
contrato público; uma interface mantém o contrato mínimo e estável, e
permite compor múltiplas customizações. Template Method ainda se justifica
**dentro** do próprio código da biblioteca (não exposto ao consumidor)
quando você quer compartilhar um algoritmo fixo entre implementações
internas.

---

## LIB.2 — Auto-configuração condicional vs beans fixos que o consumidor não consegue sobrescrever

**❓ A decisão:** seu starter registra um bean (um `RestTemplate`, um
`ObjectMapper` configurado). O que acontece se a aplicação consumidora já
declarou o próprio bean do mesmo tipo?

### 🔀 Antipadrão — bean sempre registrado, sem condição

```java
@Configuration
public class MeuStarterAutoConfiguration {
    @Bean
    public ObjectMapper objectMapper() { // registrado incondicionalmente
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
```

❌ Se a aplicação já tem seu próprio `@Bean ObjectMapper`, o resultado é
ambiguidade de bean duplicado — ou, dependendo da ordem de carregamento das
auto-configurations, o bean do starter silenciosamente vence e sobrescreve
uma configuração que o time já tinha, um comportamento surpreendente e
difícil de depurar em produção.

### 🔀 Correto — condicional, com o consumidor sempre tendo a palavra final

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "meustarter", name = "enabled", matchIfMissing = true)
public class MeuStarterAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean // só registra se o consumidor não definiu o seu
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
```

Para ajustes pontuais, exponha `@ConfigurationProperties` prefixadas (ver
[CFG.1](08-configuracao-e-externalizacao.md#cfg1--configurationproperties-tipado-vs-value-espalhado))
em vez de forçar o consumidor a redefinir o bean inteiro só para mudar um
valor.

### ✅ Veredito

Todo bean que um starter registra deve ser `@ConditionalOnMissingBean` (e o
recurso como um todo, opcionalmente `@ConditionalOnProperty` para
liga/desliga). O consumidor sempre tem a palavra final — "convenção sobre
configuração" significa fornecer um default razoável, nunca impor uma
configuração que não pode ser trocada.

---

## LIB.3 — Superfície pública mínima vs expor tudo como `public` "para não ter que pensar"

**❓ A decisão:** uma classe de implementação interna (um parser, um cache
auxiliar, um validador de assinatura) deve ser `public` só porque é mais
simples de declarar assim, ou o acesso deve ser deliberadamente restrito?

### 🔀 Antipadrão — tudo `public` por padrão

```java
// meulib.internal — mas ainda assim public
public class RetryWithBackoff { /* detalhe de implementação, nunca deveria vazar */ }
public class SignatureValidator { /* idem */ }
```

❌ Consumidores acabam dependendo dessas classes "acidentalmente expostas"
(o autocomplete da IDE simplesmente as mostra junto com a API real), e a
partir daí a biblioteca não consegue mais refatorar ou remover essas
classes sem uma quebra de compatibilidade — a superfície pública real
acabou ficando maior do que o que o mantenedor projetou como contrato.

### 🔀 Correto — visibilidade restrita por padrão, `public` como decisão deliberada

```java
class RetryWithBackoff { /* package-private: implementação, não contrato */ }

// module-info.java
module com.empresa.meulib {
    exports com.empresa.meulib.api;      // única superfície pública real
    // com.empresa.meulib.internal NÃO é exportado — inacessível fora do módulo,
    // mesmo que as classes lá dentro sejam `public`
}
```

Sem Java Módulos, ao menos isole implementação em um pacote claramente
nomeado (`internal`/`impl`) e documente explicitamente (Javadoc/README) que
classes ali não fazem parte do contrato e podem mudar sem aviso.

### ✅ Veredito

Trate `public` como uma decisão deliberada de contrato, não o padrão por
preguiça — cada classe/método público é algo que a biblioteca terá que
manter compatível "para sempre" (ver [API.3](04-design-de-api-e-contratos.md#api3--versionamento-de-contrato-público-semver--deprecated),
semver). Prefira `module-info.java` com `exports` explícito quando o build
permite; na ausência disso, isole implementação em pacotes claramente
marcados como internos.

---

## LIB.4 — Fail-fast na inicialização vs falha silenciosa/tardia em biblioteca

**❓ A decisão:** sua biblioteca é inicializada com uma configuração
inválida (URL vazia, credencial ausente). Ela deve validar isso no momento
da construção, ou só falhar — com uma `NullPointerException` genérica —
quando o método correspondente for finalmente chamado, talvez dias depois?

### 🔀 Antipadrão — aceita configuração inválida sem reclamar

```java
public class ApiClient {
    private final String apiKey;

    public ApiClient(String apiKey) {
        this.apiKey = apiKey; // aceita null/vazio sem validar
    }

    public Response call(Request r) {
        return http.send(r.withHeader("Authorization", apiKey)); // NPE aqui, na primeira chamada real
    }
}
```

❌ O erro real (configuração ausente) aparece longe da causa raiz — muitas
vezes em produção, na primeira chamada real, e não no boot da aplicação,
onde seria trivial de corrigir e barato de detectar em CI/staging.

### 🔀 Correto — valida na construção, falha imediatamente com mensagem clara

```java
public class ApiClient {
    private final String apiKey;

    public ApiClient(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey não pode ser nulo");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey não pode ser vazio");
        }
    }
}
```

Em um Spring Boot starter, o equivalente é falhar a auto-configuração cedo
(`@PostConstruct`, ou uma validação no próprio `@ConfigurationProperties`
com Bean Validation) — o boot da aplicação falha com uma mensagem clara,
em vez de deixar o problema esperando para acontecer.

### ✅ Veredito

Bibliotecas devem falhar o mais cedo possível — na construção, na
auto-configuração, no boot — e nunca no primeiro uso real. Um erro de
configuração que derruba a inicialização com uma mensagem clara é
infinitamente mais barato de corrigir do que uma falha silenciosa ou uma
exceção genérica descoberta em produção meses depois.
