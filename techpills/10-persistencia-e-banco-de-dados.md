# 🗄️ Persistência e Banco de Dados

[← Voltar ao índice](../README.md)

---

## DB.1 — Versionamento de schema: Flyway/Liquibase vs `ddl-auto: update`

**❓ A decisão:** como o schema do banco evolui junto com o código, através de
ambientes e de um time inteiro?

### 🔀 Antipadrão — Hibernate inferindo e aplicando o schema sozinho

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update # Hibernate infere e aplica mudanças de schema sozinho
```

❌ Sem controle de versão do schema, sem histórico auditável, sem revisão em
PR do que efetivamente muda no banco. ❌ O Hibernate pode inferir mudanças de
forma inesperada (não remove colunas obsoletas, escolhe tipos de coluna que
não são os que você escolheria manualmente) e não há garantia de que o schema
resultante seja idêntico entre dev, CI, staging e produção.

### 🔀 Correto — migrations versionadas, aplicadas de forma determinística

```sql
-- V12__add_discount_column_to_orders.sql
ALTER TABLE orders ADD COLUMN discount_cents BIGINT NOT NULL DEFAULT 0;
```

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate # Hibernate só CONFERE que o schema bate com as entidades — nunca aplica DDL
  flyway:
    enabled: true
```

✅ Cada mudança de schema é um artefato versionado, revisado em PR como
qualquer outro código, e aplicado de forma **idêntica e determinística** em
todo ambiente, com histórico auditável de quem mudou o quê e quando.

### ✅ Veredito

`ddl-auto: update` é aceitável **apenas** em ambiente de desenvolvimento
local individual — nunca além disso. Em qualquer ambiente compartilhado (CI,
staging, produção), use uma ferramenta de migration versionada (Flyway ou
Liquibase) e configure `ddl-auto: validate`, para que o Hibernate apenas
confira consistência entre entidades e schema, sem nunca ter permissão de
aplicar DDL por conta própria.

---

## DB.2 — N+1 queries: lazy padrão vs `JOIN FETCH`/`@EntityGraph`

**❓ A decisão:** como buscar associações (`@ManyToOne`, `@OneToMany`) sem
disparar uma query por linha iterada?

### 🔀 O bug clássico — lazy loading disparado dentro de um loop

```java
List<Order> orders = orderRepository.findAll(); // 1 query
for (Order order : orders) {
    order.getCustomer().getName(); // +1 query POR pedido — N+1 clássico
}
```

❌ Invisível em ambiente de desenvolvimento com poucos dados (2-3 pedidos,
2-3 queries extras passam despercebidas); catastrófico em produção com
volume real (10 mil pedidos = 10 mil queries extras numa única requisição).

### 🔀 `JOIN FETCH` explícito, na query que sabe que vai precisar

```java
@Query("SELECT o FROM Order o JOIN FETCH o.customer WHERE o.status = :status")
List<Order> findByStatusWithCustomer(@Param("status") OrderStatus status);
```

### 🔀 `@EntityGraph`, para reaproveitar o mesmo agregado com fetch plans diferentes por caso de uso

```java
@EntityGraph(attributePaths = {"customer", "items"})
@Query("SELECT o FROM Order o WHERE o.status = :status")
List<Order> findByStatusEager(@Param("status") OrderStatus status);
```

### ✅ Veredito

Mantenha associações como `LAZY` por padrão, sempre — nunca `EAGER` global no
mapeamento da entidade. Trocar `LAZY` por `EAGER` na entidade inteira só
substitui N+1 por *over-fetching* sistemático: toda consulta, mesmo as que
não precisam da associação, passa a carregá-la. Para o caso de uso específico
que sabe, de antemão, que vai acessar a associação, use `JOIN FETCH` ou
`@EntityGraph` **naquela query pontual**. Valide com um teste de contagem de
queries (estatísticas do Hibernate, ou `datasource-proxy`) nos endpoints
críticos — N+1 é silencioso até o dia em que o volume de dados em produção o
torna óbvio, e nesse dia já é um incidente, não um code review.

---

## DB.3 — Paginação: offset vs keyset/cursor

**❓ A decisão:** como paginar uma listagem que pode crescer para milhões de
linhas?

### 🔀 `OFFSET`/`LIMIT`

```java
@Query("SELECT o FROM Order o ORDER BY o.createdAt DESC")
Page<Order> findAll(Pageable pageable); // internamente: OFFSET x LIMIT y
```

❌ `OFFSET` faz o banco escanear e descartar todas as linhas anteriores à
página pedida — o custo cresce linearmente com o número da página; a página
5000 é sensivelmente mais lenta que a página 1. ❌ Instável sob escrita
concorrente: uma inserção entre duas requisições de página desloca todos os
offsets seguintes, causando itens duplicados ou pulados na navegação.

### 🔀 Keyset/cursor pagination (seek method)

```java
@Query("SELECT o FROM Order o WHERE o.createdAt < :cursor ORDER BY o.createdAt DESC")
List<Order> findPageAfter(@Param("cursor") Instant cursor, Pageable pageable);

public record OrderPage(List<Order> items, Instant nextCursor) {}
```

✅ Custo constante independente da profundidade da página — usa o índice na
coluna de ordenação diretamente, sem escanear e descartar nada. ✅ Estável sob
escrita concorrente, porque não depende de posição numérica, só do último
valor visto pelo cliente.

### ✅ Veredito

Para listas pequenas/médias com necessidade real de "ir direto para a página
N" (uma UI de admin com paginação numerada), offset/limit é simples e
suficiente. Para feeds de alto volume, rolagem infinita, ou qualquer API
pública de escala relevante, prefira keyset/cursor pagination desde o design
inicial — trocar de offset para cursor depois que a API já tem consumidores
externos é uma mudança de contrato dolorosa (ver [API.3](04-design-de-api-e-contratos.md#api3--versionamento-de-contrato-público-semver--deprecated)),
então vale decidir isso corretamente na primeira versão.

---

## DB.4 — Connection pool sizing (HikariCP): quantas conexões?

**❓ A decisão:** qual `maximum-pool-size` configurar para o pool de conexões
de banco?

### 🔀 Antipadrão — "quanto mais, melhor, só pra garantir"

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 200
```

❌ Mais conexões que os núcleos disponíveis no servidor de banco conseguem
processar em paralelo não aumentam throughput — pelo contrário: aumentam
contenção (troca de contexto, disputa de lock internos do banco) e podem
**piorar** a performance geral sob carga. ❌ Multiplicado pelo número de
instâncias da aplicação quando ela escala horizontalmente, um pool grande por
instância esgota rapidamente o `max_connections` total do banco.

### 🔀 Dimensionamento baseado em medição, não em intuição

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10  # ponto de partida típico; ajustar com métricas reais de saturação
      minimum-idle: 10
      connection-timeout: 3000
```

### ✅ Veredito

Pool grande não é sinônimo de mais capacidade. Comece pequeno — a própria
documentação da HikariCP ("About Pool Sizing") sugere uma fórmula de
referência próxima de `núcleos disponíveis no banco × 2 + discos efetivos`
para cargas transacionais típicas — e ajuste com base em métricas reais de
saturação do pool (tempo de espera por uma conexão livre), nunca por
intuição de "quanto mais, melhor". Em arquiteturas com múltiplas instâncias
da aplicação, sempre calcule o total (`instâncias × maximum-pool-size`)
contra o `max_connections` real do banco — é fácil esgotar o banco ao
escalar a aplicação horizontalmente sem revisar esse número.
