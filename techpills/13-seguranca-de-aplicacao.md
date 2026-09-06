# 🔐 Segurança de Aplicação

[← Voltar ao índice](../README.md)

---

## SEC.1 — Hash de senha: BCrypt/Argon2 vs SHA/MD5

**❓ A decisão:** com qual função a senha do usuário deve ser transformada
antes de ir para o banco?

### 🔀 Antipadrão — hash de propósito geral, mesmo com salt

```java
String hash = DigestUtils.sha256Hex(password + salt); // RÁPIDO — exatamente o problema
```

❌ SHA-256/MD5 foram desenhados para serem **rápidos** — ótimo para checksum
de arquivo, péssimo para senha. Um atacante com a base vazada consegue testar
bilhões de combinações por segundo em uma GPU comum, tornando ataques de
força bruta/dicionário triviais mesmo com salt (o salt impede *rainbow
tables*, mas não torna a busca por força bruta mais lenta).

### 🔀 Correto — função de hash de senha deliberadamente lenta

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12); // fator de custo ajustável conforme hardware disponível
}

boolean matches = passwordEncoder.matches(rawPassword, storedHash);
```

### ✅ Veredito

Nunca use uma função de hash de propósito geral (MD5, família SHA) para
senha, mesmo com salt. Use sempre uma função desenhada especificamente para
isso — BCrypt, Scrypt, ou preferencialmente **Argon2id** (vencedor da
Password Hashing Competition) — que é deliberadamente lenta e cara de
computar, tornando ataques de força bruta impraticáveis em escala. O fator de
custo (rounds do BCrypt; memória/iterações do Argon2) deve ser recalibrado
periodicamente conforme hardware de ataque fica mais barato — o que era
seguro em 2015 pode não ser hoje.

---

## SEC.2 — JWT stateless vs sessão de servidor

**❓ A decisão:** a estratégia de autenticação de uma API deve ser um token
JWT sem estado guardado no servidor, ou uma sessão de servidor tradicional?

### 🔀 JWT stateless

```java
String token = Jwts.builder()
    .setSubject(user.getId().toString())
    .claim("roles", user.getRoles())
    .setExpiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
    .signWith(secretKey)
    .compact();
```

✅ Escala horizontalmente sem estado compartilhado — qualquer instância
valida o token sozinha, sem consultar nada. ❌ **Revogar** um token antes da
expiração é difícil por design (o servidor não guarda estado sobre tokens
emitidos): se um token vazar, ele continua válido até expirar, a menos que
você mantenha uma *blocklist* — o que reintroduz exatamente o estado
compartilhado que o JWT tentava evitar.

### 🔀 Sessão de servidor (stateful)

```java
@PostMapping("/login")
public ResponseEntity<Void> login(HttpServletRequest request, @RequestBody Credentials credentials) {
    // ... autentica
    request.getSession().setAttribute("userId", user.getId()); // estado no servidor (ou Redis)
    return ResponseEntity.ok().build();
}
```

✅ Revogação é trivial e imediata (apagar a sessão do armazenamento). ❌ Exige
armazenamento de sessão compartilhado (Redis) entre instâncias para escalar
horizontalmente — mais uma peça de infraestrutura a operar.

### ✅ Veredito

Use JWT quando tokens precisam atravessar fronteiras de serviços/domínios
(um serviço de auth central emite, N microsserviços independentes validam
sem chamada de rede de volta ao emissor) e a janela de exposição de um token
vazado é aceitável dado um tempo de expiração curto (minutos) combinado com
refresh token revogável. Use sessão de servidor stateful quando revogação
imediata é um requisito de segurança real (ex.: "deslogar todos os
dispositivos agora") e você já opera um armazenamento compartilhado (Redis)
de qualquer forma. Na dúvida, para uma aplicação web tradicional de domínio
único, sessão stateful com Redis costuma ser mais simples e mais segura por
padrão do que um JWT mal implementado tentando reinventar revogação.

---

## SEC.3 — Autorização: checagem espalhada nos controllers vs política centralizada

**❓ A decisão:** onde vive a regra de "quem pode ver/fazer o quê" — ad-hoc em
cada endpoint, ou numa política centralizada e reaproveitada?

### 🔀 Antipadrão — checagem duplicada, escrita à mão em cada endpoint

```java
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable Long id, Principal principal) {
    Order order = orderRepository.findById(id).orElseThrow();
    if (!order.getCustomerId().equals(principal.getName()) && !isAdmin(principal)) {
        throw new AccessDeniedException("Sem permissão");
    }
    // essa checagem precisa ser lembrada em TODO endpoint que toca em Order
    return mapper.toResponse(order);
}
```

❌ Fácil de esquecer num endpoint novo — cada checagem é reimplementada
manualmente, e uma omissão vira uma vulnerabilidade de **IDOR** (Insecure
Direct Object Reference) silenciosa: um usuário acessando dado de outro só
trocando o `id` na URL.

### 🔀 Política de autorização declarativa e centralizada

```java
@PreAuthorize("@orderSecurity.canView(#id, principal)")
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable Long id) {
    return mapper.toResponse(orderRepository.findById(id).orElseThrow());
}

@Component("orderSecurity")
public class OrderSecurityPolicy {
    public boolean canView(Long orderId, Principal principal) {
        // regra escrita UMA vez, testável isoladamente, reaproveitada em todo endpoint de Order
        return orderRepository.isOwner(orderId, principal.getName()) || hasAdminRole(principal);
    }
}
```

### ✅ Veredito

Centralize regras de **autorização** (o que um usuário autenticado pode
fazer — diferente de **autenticação**, que é confirmar quem ele é) num único
lugar testável: uma classe de política, referenciada via `@PreAuthorize` com
SpEL, ou um mecanismo de policy equivalente — nunca reimplementada ad-hoc em
cada controller. Isso não é só sobre duplicação de código: é sobre superfície
de ataque. Toda checagem de autorização escrita à mão de novo é mais uma
chance de esquecer, e o resultado típico dessa omissão é exposição de dado de
outro usuário (IDOR) — uma das falhas mais recorrentes do OWASP Top 10.
