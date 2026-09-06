# ⚙️ Configuração e Externalização

[← Voltar ao índice](../README.md)

---

## CFG.1 — `@ConfigurationProperties` tipado vs `@Value` espalhado

**❓ A decisão:** propriedades de `application.yml` devem ser lidas com `@Value`
pontual em cada classe que precisa, ou agrupadas num objeto de configuração
tipado?

### 🔀 Opção A — `@Value` espalhado

```java
@Service
public class EmailService {
    @Value("${mail.smtp.host}") private String smtpHost;
    @Value("${mail.smtp.port}") private int smtpPort;
    @Value("${mail.smtp.timeout:5000}") private int timeout;
}
```

❌ Nenhuma validação central — um erro de digitação na chave (`mail.stmp.host`)
não quebra a build nem a subida da aplicação, só se manifesta quando o campo
fica com valor default/nulo em runtime. ❌ A mesma propriedade, se usada em
duas classes, é lida (e pode divergir) em dois lugares.

### 🔀 Opção B — `@ConfigurationProperties` como record imutável

```java
@ConfigurationProperties(prefix = "mail.smtp")
@Validated
public record MailProperties(
    @NotBlank String host,
    int port,
    @DefaultValue("5000") int timeout
) {}
```

```java
@Configuration
@EnableConfigurationProperties(MailProperties.class)
class MailConfig { }
```

✅ Um único ponto de verdade, tipado e validado (Bean Validation) na
inicialização — a aplicação falha ao **subir** se a config estiver incompleta
ou inválida, não na primeira chamada em produção. ✅ Testável com
`new MailProperties("smtp.exemplo.com", 587, 5000)`, sem precisar de contexto
Spring.

### ✅ Veredito

Use `@ConfigurationProperties` (idealmente como `record` imutável) sempre que
houver mais de 2-3 propriedades relacionadas, ou quando a configuração tiver
estrutura (objetos aninhados, listas). Reserve `@Value` para um valor solto e
verdadeiramente isolado, sem relação com outros. Valide sempre fail-fast na
subida da aplicação — errar cedo no bootstrap é ordens de magnitude mais
barato que descobrir uma config quebrada na primeira requisição real em
produção.

---

## CFG.2 — Segredos: cofre de segredos vs variável de ambiente vs valor no `application.yml`

**❓ A decisão:** onde ficam credenciais de banco, chaves de API e outros
segredos?

### 🔀 Antipadrão — segredo versionado em texto claro

```yaml
spring:
  datasource:
    password: "S3nhaSuperSecreta123" # nunca — isso fica no histórico do git PARA SEMPRE
```

❌ Mesmo removido de um commit futuro, o valor continua recuperável no
histórico do repositório indefinidamente. ❌ "É só o ambiente de dev" não é
proteção — ambientes de dev viram produção por acidente, e credenciais são
frequentemente reaproveitadas entre ambientes por preguiça.

### 🔀 Variável de ambiente injetada pela plataforma

```yaml
spring:
  datasource:
    password: ${DB_PASSWORD}
```

✅ Nunca versionado; o valor real vem de um `Secret` do Kubernetes, de uma
variável de ambiente do provedor de PaaS, etc. Suficiente para a maioria dos
casos.

### 🔀 Cofre de segredos dedicado (rotação e auditoria)

```java
// Spring Cloud Vault, AWS Secrets Manager, etc. — segredo buscado em runtime,
// nunca versionado, com suporte a rotação automática e trilha de auditoria
// de quem/o que acessou cada segredo e quando.
```

### ✅ Veredito

**Nunca** commit um segredo em texto claro em nenhum arquivo versionado, em
nenhuma circunstância. Para a maioria dos casos, variável de ambiente
injetada pela plataforma de deploy já resolve. Para segredos que precisam
rotacionar automaticamente ou exigem auditoria fina de acesso (credenciais de
banco de produção, chaves de assinatura), use um cofre dedicado. Trate todo
`application.yml` versionado como **público**, mesmo em repositório privado —
o controle de acesso ao repositório não é o mesmo controle de acesso a um
segredo de produção.

---

## CFG.3 — Perfis (`@Profile`) vs feature flag em runtime

**❓ A decisão:** um comportamento precisa variar. Isso é resolvido com
`@Profile` (decidido no deploy) ou com uma feature flag (decidida em
runtime)?

### 🔀 `@Profile` — diferença estrutural fixa por ambiente

```java
@Service
@Profile("!test")
public class RealPaymentGateway implements PaymentGateway { /* ... */ }

@Service
@Profile("test")
public class FakePaymentGateway implements PaymentGateway { /* ... */ }
```

Correto para: qual implementação de infraestrutura usar (banco real vs
in-memory, gateway real vs fake) — uma decisão tomada **no momento do
deploy**, que não muda sem reiniciar a aplicação com outro perfil ativo.

### 🔀 Feature flag — comportamento de negócio ligável em runtime

```java
@Service
public class CheckoutService {
    private final FeatureFlags flags;

    public void checkout(Order order) {
        if (flags.isEnabled("new-pricing-engine", order.getCustomerId())) {
            newPricingEngine.calculate(order);
        } else {
            legacyPricingEngine.calculate(order);
        }
    }
}
```

Correto para: comportamento de produto que precisa ser ligado/desligado, ou
liberado gradualmente (rollout percentual, por cliente, por tenant) **sem
novo deploy**.

### ✅ Veredito

Use `@Profile` para diferenças estruturais de infraestrutura fixadas no
deploy. Use feature flag para comportamento de negócio que o time de produto
precisa poder ligar, desligar ou reverter em produção sem esperar um pipeline
de deploy inteiro. Usar `@Profile` para algo que na verdade precisava ser
toggleable em runtime é um erro caro: toda vez que o negócio quiser testar ou
reverter algo, alguém vai precisar de um redeploy — lento e arriscado demais
para decisão de produto do dia a dia.
