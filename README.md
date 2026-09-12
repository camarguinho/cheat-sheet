# ☕ Java Engineering Decisions — Techpills

Um conglomerado de **techpills**: decisões técnicas críticas que aparecem
repetidamente na vida de quem projeta bibliotecas, starters, serviços e
sistemas em Java. Cada pill não é "a resposta certa universal" — é uma
**posição fundamentada**, com o raciocínio explícito, para você concordar,
discordar ou adaptar ao seu contexto com conhecimento de causa.

O objetivo não é decorar vereditos. É internalizar o *porquê* de cada um,
para que você consiga defender (ou refutar) qualquer um deles numa revisão
de design com um time sênior.

## Como cada pill é estruturada

```
❓ A decisão      → a pergunta que efetivamente separa engenheiros júnior de sênior
📍 Contexto       → quando essa decisão aparece de verdade
🔀 Opções         → cada abordagem, com código Java e trade-offs
✅ Veredito       → a recomendação padrão + quando desviar dela
```

> Veredito ≠ dogma. É o *default* razoável quando você não tem informação
> adicional. Contexto (escala, time, prazo, criticidade) sempre pode mudar
> a resposta — mas só depois de você entender o que está trocando.

## Índice

### [01 — Injeção de Dependência](techpills/01-injecao-de-dependencia.md)
- DI.1 — Biblioteca reutilizável: `@Autowired` em campo vs injeção via construtor
- DI.2 — Injeção via construtor vs via setter
- DI.3 — Múltiplas implementações de uma interface: `@Qualifier` vs Strategy + registro
- DI.4 — Service Locator vs dependência explícita

### [02 — Tratamento de Erros](techpills/02-tratamento-de-erros.md)
- ERR.1 — Exceções *checked* vs *unchecked* em API pública
- ERR.2 — `Optional<T>` vs `null` vs exceção para "ausência de valor"
- ERR.3 — Result/Either vs exceção para fluxo de erro esperado (validação)
- ERR.4 — Propagar vs empacotar (`wrap`) exceções entre camadas

### [03 — Imutabilidade e Objetos de Valor](techpills/03-imutabilidade-e-objetos-de-valor.md)
- VAL.1 — `record` vs Lombok `@Value` vs classe manual
- VAL.2 — Builder vs construtores telescópicos vs *static factory methods*
- VAL.3 — `equals`/`hashCode` em entidades JPA: identidade, chave de negócio ou todos os campos?
- VAL.4 — Cópia defensiva vs coleção imutável exposta

### [04 — Design de API e Contratos](techpills/04-design-de-api-e-contratos.md)
- API.1 — Expor entidade de domínio/JPA na REST vs DTO na borda
- API.2 — Interface vs classe abstrata como ponto de extensão (SPI)
- API.3 — Versionamento de contrato público: semver + `@Deprecated`
- API.4 — Bean Validation no DTO vs invariante no construtor do domínio

### [05 — Concorrência](techpills/05-concorrencia.md)
- CON.1 — `synchronized` vs `java.util.concurrent` (`ConcurrentHashMap`, `Atomic*`, `Lock`)
- CON.2 — Singleton *stateless* vs estado mutável por instância
- CON.3 — `CompletableFuture` (assíncrono) vs chamada bloqueante para bibliotecas
- CON.4 — `ThreadLocal`: quando usar e o risco em pools de threads

### [06 — Arquitetura e Fronteiras](techpills/06-arquitetura-e-fronteiras.md)
- ARC.1 — *Package by layer* vs *package by feature*
- ARC.2 — Domínio livre de framework vs domínio anêmico acoplado a Spring/JPA
- ARC.3 — Dependência circular entre módulos: como quebrar de verdade

### [07 — Testes](techpills/07-testes.md)
- TST.1 — Mockist (London) vs Classicist (Detroit)
- TST.2 — Testcontainers vs H2 in-memory em testes de integração
- TST.3 — Mock vs Stub vs Fake: quando usar cada test double

### [08 — Configuração e Externalização](techpills/08-configuracao-e-externalizacao.md)
- CFG.1 — `@ConfigurationProperties` tipado vs `@Value` espalhado
- CFG.2 — Segredos: cofre de segredos vs variável de ambiente vs valor no `application.yml`
- CFG.3 — Perfis (`@Profile`) vs feature flag em runtime

### [09 — Observabilidade e Logging](techpills/09-observabilidade-e-logging.md)
- OBS.1 — SLF4J facade vs acoplar Logback/Log4j2 diretamente em biblioteca
- OBS.2 — Log estruturado (JSON/MDC) vs texto livre
- OBS.3 — Métricas (RED/USE) vs inferir saúde do sistema só pelo log
- OBS.4 — Health checks: liveness vs readiness
- OBS.5 — Nível de log único (root) vs loggers segmentados por pacote/módulo em produção

### [10 — Persistência e Banco de Dados](techpills/10-persistencia-e-banco-de-dados.md)
- DB.1 — Versionamento de schema: Flyway/Liquibase vs `ddl-auto: update`
- DB.2 — N+1 queries: lazy padrão vs `JOIN FETCH`/`@EntityGraph`
- DB.3 — Paginação: offset vs keyset/cursor
- DB.4 — Connection pool sizing (HikariCP): quantas conexões?

### [11 — Transações e Consistência](techpills/11-transacoes-e-consistencia.md)
- TRX.1 — `@Transactional`: escopo e propagation (`REQUIRED` vs `REQUIRES_NEW`)
- TRX.2 — Transação distribuída (2PC) vs padrão Outbox/Saga
- TRX.3 — Transaction script "gordo" vs escopo transacional estreito

### [12 — Comunicação entre Serviços](techpills/12-comunicacao-entre-servicos.md)
- COM.1 — Síncrono (REST/gRPC) vs assíncrono (mensageria) entre serviços
- COM.2 — Idempotência em endpoints não naturalmente idempotentes
- COM.3 — Resiliência: timeout + circuit breaker vs chamada "nua" a serviço externo

### [13 — Segurança de Aplicação](techpills/13-seguranca-de-aplicacao.md)
- SEC.1 — Hash de senha: BCrypt/Argon2 vs SHA/MD5
- SEC.2 — JWT stateless vs sessão de servidor
- SEC.3 — Autorização: checagem espalhada nos controllers vs política centralizada

### [14 — Patterns Importantes para Aplicações](techpills/14-patterns-para-aplicacoes.md)
- APP.1 — Strategy vs `if`/`switch` encadeado para variações de regra de negócio
- APP.2 — Chain of Responsibility para pipelines vs método monolítico com validações em sequência
- APP.3 — Domain Events (Observer) vs acoplamento direto entre casos de uso
- APP.4 — Application Service (Facade) vs lógica de negócio dentro do Controller

### [15 — Patterns Importantes para Componentes Reutilizáveis](techpills/15-patterns-para-componentes-reutilizaveis.md)
- LIB.1 — Extensibilidade: Template Method (herança) vs Strategy/SPI (composição)
- LIB.2 — Auto-configuração condicional vs beans fixos que o consumidor não consegue sobrescrever
- LIB.3 — Superfície pública mínima vs expor tudo como `public` "para não ter que pensar"
- LIB.4 — Fail-fast na inicialização vs falha silenciosa/tardia em biblioteca

---

Contribuições: ao adicionar uma pill nova, siga o template acima e garanta
que o veredito responde explicitamente **"quando isso NÃO se aplica"** —
uma recomendação sem exceções documentadas normalmente significa que ela
não foi pensada até o fim.
