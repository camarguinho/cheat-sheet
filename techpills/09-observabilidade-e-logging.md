# 📡 Observabilidade e Logging

[← Voltar ao índice](../README.md)

---

## OBS.1 — SLF4J facade vs acoplar Logback/Log4j2 diretamente em biblioteca

**❓ A decisão:** uma biblioteca precisa logar algo internamente. Ela deve
depender da API do SLF4J, ou de uma implementação concreta de logging?

### 🔀 Antipadrão — biblioteca acoplada à implementação

```java
import ch.qos.logback.classic.Logger; // biblioteca importando a IMPLEMENTAÇÃO

public class HttpClient {
    private static final Logger log = (Logger) LoggerFactory.getLogger(HttpClient.class);
    // todo consumidor desta biblioteca é forçado a ter Logback no classpath,
    // mesmo que use Log4j2 ou java.util.logging na própria aplicação
}
```

### 🔀 Correto — apenas a fachada SLF4J

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpClient {
    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    public String send(Request request) {
        log.debug("Enviando requisição para {}", request.url());
        return doSend(request);
    }
}
```

### ✅ Veredito

Bibliotecas devem depender apenas da **API** do SLF4J (`org.slf4j:slf4j-api`),
nunca de uma implementação concreta (Logback, Log4j2, `java.util.logging`).
Quem decide qual implementação usar — e como configurá-la (formato, nível,
destino) — é a aplicação final, no seu próprio classpath, com seu próprio
binding. É exatamente o mesmo princípio de [DI.1](01-injecao-de-dependencia.md#di1--biblioteca-reutilizável-autowired-em-campo-vs-injeção-via-construtor)
aplicado a logging: framework/implementação concreta é decisão de quem monta
a aplicação, não de quem escreve a biblioteca reutilizável.

---

## OBS.2 — Log estruturado (JSON/MDC) vs texto livre

**❓ A decisão:** logs vão ser consumidos por humanos lendo terminal, ou por
uma ferramenta de observabilidade centralizada (ELK, Datadog, Loki)?

### 🔀 Texto livre concatenado

```java
log.info("Pedido " + order.getId() + " processado para cliente " + customerId
    + " no valor de " + total);
```

❌ Cada campo relevante fica preso dentro de uma string livre — extrair
`orderId` ou `total` depois exige regex frágil, difícil de indexar, filtrar
ou agregar numa ferramenta de log centralizado.

### 🔀 Log estruturado, com MDC para contexto transversal

```java
MDC.put("orderId", order.getId().toString());
MDC.put("customerId", customerId.toString());
try {
    log.info("Pedido processado", kv("total", total)); // logstash-logback-encoder
} finally {
    MDC.clear(); // ver CON.4 sobre o risco de esquecer isso em pool de threads
}
```

Com um encoder JSON (`logstash-logback-encoder`), cada linha de log vira um
documento JSON pesquisável campo a campo — `orderId`, `customerId` e `total`
tornam-se filtros diretos, não regex sobre texto.

### ✅ Veredito

Para qualquer serviço com volume relevante de logs enviados a uma stack de
observabilidade centralizada, **log estruturado é o padrão** — permite
filtrar, agregar e correlacionar por campo (em especial `traceId`, para
seguir uma requisição através de múltiplos serviços). Log de texto livre
concatenado só se justifica em scripts pequenos/CLI sem nenhuma
infraestrutura de observabilidade por trás.

---

## OBS.3 — Métricas (RED/USE) vs tentar inferir saúde do sistema só pelo log

**❓ A decisão:** como saber que "algo está errado" em produção — grepando
volume de log `ERROR`, ou instrumentando métricas dedicadas?

### 🔀 Antipadrão — alerta baseado em contagem de linhas de log

```java
log.error("Falha ao processar pedido {}", order.getId(), exception);
// "alerta" configurado como: "se aparecerem mais de 50 linhas ERROR em 5 min"
```

❌ Lento (depende do pipeline de ingestão de log terminar de processar),
caro em escala, e frágil — uma mudança de texto na mensagem de log quebra o
alerta sem ninguém perceber.

### 🔀 Métricas dedicadas (RED: Rate, Errors, Duration), via Micrometer

```java
@Timed(value = "orders.process", description = "tempo de processamento de pedidos")
public OrderResult process(Order order) {
    return orderProcessor.execute(order);
}

Counter.builder("orders.rejected")
    .tag("reason", rejection.reason())
    .register(registry)
    .increment();
```

✅ Consultável quase em tempo real, barato de agregar (percentis, taxas),
naturalmente compatível com dashboards e alertas baseados em limiar/tendência
— sem depender de parsing de texto.

### ✅ Veredito

Logs são para **investigar um evento específico** depois que você já sabe (ou
suspeita) que algo aconteceu — servem à pergunta "o que aconteceu exatamente
com o pedido #4471?". Métricas são para **saber que algo está acontecendo**
sem precisar ler log nenhum — servem à pergunta "a taxa de erro está normal
agora?". Instrumente pelo menos os sinais RED (taxa de requisições, taxa de
erro, duração/latência) em toda operação relevante de negócio, e configure
alertas sobre as métricas — nunca sobre contagem de linhas de log como
mecanismo primário de alerta.

---

## OBS.4 — Health checks: liveness vs readiness

**❓ A decisão:** um único health check genérico serve tanto para dizer ao
orquestrador "reinicie este pod" quanto "não mande tráfego pra este pod
agora"?

### 🔀 Antipadrão — uma checagem única para os dois propósitos

```java
@Component
public class DatabaseHealthIndicator implements HealthIndicator {
    public Health health() {
        // se o banco cair, ESTE check falha
    }
}
// usado como liveness E readiness ao mesmo tempo
```

❌ Se usado como *liveness probe*, o Kubernetes reinicia o pod porque o banco
externo está fora do ar — mas o pod em si não tinha problema nenhum;
reiniciá-lo não conserta o banco, e ainda gera uma tempestade de restarts
desnecessários sob um incidente que já é ruim o bastante.

### 🔀 Correto — liveness e readiness com responsabilidades separadas

```java
// liveness: só confirma que o processo em si está vivo e não travado internamente
@Component
public class LivenessHealthIndicator implements HealthIndicator {
    public Health health() { return Health.up().build(); }
}

// readiness: confirma que as dependências necessárias para atender tráfego estão OK
@Component
public class ReadinessHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;

    public Health health() {
        try (Connection c = dataSource.getConnection()) {
            return Health.up().build();
        } catch (SQLException e) {
            return Health.down(e).build(); // tira o pod da rotação, SEM reiniciá-lo
        }
    }
}
```

Com Spring Boot Actuator: `management.endpoint.health.probes.enabled=true`
expõe `/actuator/health/liveness` e `/actuator/health/readiness`
separadamente, mapeando direto para as probes do Kubernetes.

### ✅ Veredito

**Nunca amarre a mesma checagem a liveness e readiness.** Liveness responde
"este processo está num estado do qual não vai se recuperar sozinho (deadlock,
memória corrompida, thread pool travado)?" — falha aqui justifica reiniciar o
pod. Readiness responde "consigo atender tráfego agora?" — falha aqui só tira
o pod da rotação de load balancing sem reiniciá-lo, porque a causa raiz
(dependência externa fora do ar) não se resolve reiniciando a própria
aplicação.

---

## OBS.5 — Nível de log único (root) vs loggers segmentados por pacote/módulo em produção

**❓ A decisão:** para investigar um incidente, você sobe o nível de log
**global** (root logger) para `DEBUG`/`TRACE`, ou já tem loggers segmentados
por pacote/módulo de forma que dá pra elevar o nível de **um grupo
específico**, sem afetar o restante da aplicação?

### 🔀 Antipadrão — um único nível de log para a aplicação inteira

```xml
<!-- logback.xml -->
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder><pattern>%d %-5level [%thread] %logger{36} - %msg%n</pattern></encoder>
    </appender>

    <!-- único nível para TODO o classpath -->
    <root level="DEBUG">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

❌ Ao subir o `root` para `DEBUG`/`TRACE` para rastrear a causa de **um**
incidente pontual (ex.: um bug no fluxo de checkout), você liga verbosidade
máxima em **todas as camadas**: Hibernate loga cada SQL com binding de
parâmetro, o client HTTP loga corpo de request/response inteiro, o
Spring loga resolução de bean e matching de rota — nada disso tem relação
com o incidente, mas o volume de log explode em conjunto.

Em Kubernetes isso é particularmente perigoso porque o volume extra de log
não fica "de graça": cada linha aloca `String`/`byte[]`, passa por
formatação, é enfileirada num appender e, na maioria das stacks, é
capturada por um *log shipper* (Fluent Bit/Filebeat como sidecar, ou o
próprio runtime do container lendo stdout). Se o appender é assíncrono
(`AsyncAppender`/`logstash-logback-encoder`) e a fila cresce mais rápido do
que o destino consegue drenar — disco lento, sidecar sobrecarregado, rede
lenta até o coletor — essa fila em memória cresce dentro do **mesmo limite
de memória do container**. O resultado é o pod sendo `OOMKilled` bem no
meio do incidente que você estava tentando diagnosticar, aumentando o
tempo de indisponibilidade em vez de reduzir.

### 🔀 Correto — loggers hierárquicos por pacote, ajustáveis em runtime

```xml
<!-- logback.xml: root fica conservador, grupos específicos são ajustáveis -->
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder><pattern>%d %-5level [%thread] %logger{36} - %msg%n</pattern></encoder>
    </appender>

    <!-- appender assíncrono com fila LIMITADA e política de descarte —
         nunca deixa o volume de log crescer sem limite dentro do container -->
    <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>512</queueSize>
        <discardingThreshold>20</discardingThreshold> <!-- descarta TRACE/DEBUG sob pressão -->
        <neverBlock>true</neverBlock> <!-- nunca bloqueia a thread de negócio esperando a fila -->
        <includeCallerData>false</includeCallerData> <!-- caller data é caro; NUNCA ligar em TRACE -->
        <appender-ref ref="STDOUT"/>
    </appender>

    <root level="WARN">
        <appender-ref ref="ASYNC"/>
    </root>

    <!-- grupos por domínio/módulo — cada um pode ser elevado isoladamente -->
    <logger name="com.empresa.checkout" level="INFO"/>
    <logger name="com.empresa.pagamentos" level="INFO"/>
    <logger name="org.hibernate.SQL" level="WARN"/>
    <logger name="org.springframework.web" level="WARN"/>
</configuration>
```

Com o Spring Boot Actuator, esses loggers viram ajustáveis **em runtime**,
sem redeploy e sem reiniciar o pod:

```bash
# eleva SÓ o pacote suspeito, sem tocar no resto da aplicação
curl -X POST localhost:8080/actuator/loggers/com.empresa.checkout \
     -H 'Content-Type: application/json' -d '{"configuredLevel": "DEBUG"}'

# fundamental: reverter assim que o diagnóstico terminar
curl -X POST localhost:8080/actuator/loggers/com.empresa.checkout \
     -H 'Content-Type: application/json' -d '{"configuredLevel": null}'
```

Para não depender de alguém lembrar de reverter manualmente, agende a
reversão junto com a elevação (job assíncrono, `ScheduledExecutorService`
com `TimeUnit.MINUTES`, ou automação externa que chama o endpoint de
reversão depois de N minutos) — nível de log elevado é uma condição
temporária de diagnóstico, não um novo estado permanente.

Quando o problema é **uma requisição/cliente específico** dentro de um
volume grande de tráfego, prefira nem elevar o pacote inteiro: propague o
`traceId` (ou um cabeçalho de "modo diagnóstico") via MDC e use um
`TurboFilter` que só libera `DEBUG`/`TRACE` quando aquele valor de MDC
bate — assim a verbosidade extra fica restrita à requisição investigada,
sem aumentar volume para o restante do tráfego que passa pelo mesmo
pacote.

### ✅ Veredito

Nunca controle log de produção por um único nível global. Estruture os
loggers por pacote/módulo (por domínio de negócio, não só por biblioteca
de terceiros) desde o início, com o `root` num nível conservador
(`WARN`/`INFO`) — assim, quando um incidente exigir mais detalhe, você
eleva **só o grupo suspeito**, e o aumento de volume fica restrito à parte
da aplicação que de fato importa para aquela investigação. Combine isso
com três proteções contra OOM em containers: (1) appender assíncrono com
fila **limitada** e `discardingThreshold`/`neverBlock`, para que um pico
de log jamais vire pressão de memória ilimitada dentro do limite do pod;
(2) `includeCallerData` desligado, já que captura de stack trace por
linha de log é cara e fica ainda mais cara com `TRACE` ligado; (3) reversão
automática/agendada do nível elevado, para que "ligamos TRACE durante o
incidente" não vire "esquecemos TRACE ligado por semanas". Só use elevação
de nível ampla (múltiplos pacotes, ou o `root`) como último recurso, e
mesmo assim por tempo estritamente controlado — o padrão é sempre o grupo
mais estreito que ainda resolve a pergunta que motivou a investigação.
