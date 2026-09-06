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

---

Contribuições: ao adicionar uma pill nova, siga o template acima e garanta
que o veredito responde explicitamente **"quando isso NÃO se aplica"** —
uma recomendação sem exceções documentadas normalmente significa que ela
não foi pensada até o fim.
