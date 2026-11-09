# 05 — Tratamento de erros no Spring MVC

[← HTTP status](04-http-status-codes.md) · [Índice](README.md) · [Próximo: Problem Details →](06-problem-details-rfc-9457.md)

---

## 5.1 O caminho de uma exceção dentro do Spring

```
Requisição
   │
   ▼
Filters (Servlet / Spring Security)  ── exceção aqui NÃO passa pelo @ControllerAdvice ──┐
   │                                                                                    │
   ▼                                                                                    │
DispatcherServlet                                                                       │
   │  controller → service → ... → throw                                               │
   ▼                                                                                    │
HandlerExceptionResolver (em ordem, o primeiro que resolver vence)                      │
   1. ExceptionHandlerExceptionResolver  → métodos @ExceptionHandler                    │
        a) do próprio controller                                                        │
        b) dos @ControllerAdvice (respeitando @Order)                                   │
   2. ResponseStatusExceptionResolver    → @ResponseStatus e ResponseStatusException    │
   3. DefaultHandlerExceptionResolver    → exceções padrão do Spring MVC                │
   │                                                                                    │
   ▼ ninguém resolveu                                                                   │
Container (Tomcat) ── forward ──► /error ──► BasicErrorController (Spring Boot) ◄───────┘
                                            usa ErrorAttributes + server.error.*
```

Dois fatos que essa figura deixa claros:

1. **O `@RestControllerAdvice` só enxerga o que acontece dentro do `DispatcherServlet`.** Filters
   (incluindo os do Spring Security) ficam de fora.
2. **Sempre existe uma última linha de defesa** (`/error`) e ela precisa estar configurada para
   não vazar detalhes.

## 5.2 As opções, da pior para a melhor (em uma API)

### ❌ try/catch no controller

```java
@GetMapping("/{id}")
ResponseEntity<?> get(@PathVariable UUID id) {
    try {
        return ResponseEntity.ok(service.get(id));
    } catch (NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("erro", e.getMessage()));
    } catch (Exception e) {
        return ResponseEntity.status(500).body(Map.of("erro", e.getMessage()));   // vaza detalhe
    }
}
```

Duplicado em todo endpoint, formato inconsistente, fácil de esquecer.

### ⚠️ `@ResponseStatus` na exceção

```java
@ResponseStatus(HttpStatus.NOT_FOUND)
class OrderNotFoundException extends RuntimeException {}
```

Simples, mas acopla o domínio a HTTP e gera o corpo padrão do Boot, não o seu contrato.

### ⚠️ `ResponseStatusException`

```java
throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado");
```

Bom para algo pontual **no controller**. No service, é HTTP vazando para o domínio.
(Ela é uma `ErrorResponseException` e produz Problem Detail; o handler do projeto também anota
`code` e `correlationId` nela: teste `responseStatusException_tambemSegueOContrato`.)

### ⚠️ `@ExceptionHandler` local

```java
@RestController
class OrderController {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) { ... }
}
```

Vale apenas para aquele controller. Útil para um tratamento realmente específico de um endpoint.

### ✅ `@RestControllerAdvice` global estendendo `ResponseEntityExceptionHandler`

É o que o projeto usa: [`GlobalExceptionHandler`](../src/main/java/dev/cheatsheet/exceptions/shared/web/GlobalExceptionHandler.java).

`ResponseEntityExceptionHandler` já trata **todas** as exceções padrão do Spring MVC
(`MethodArgumentNotValidException`, `HttpMessageNotReadableException`,
`HttpRequestMethodNotSupportedException`, `NoResourceFoundException`, `TypeMismatchException`...)
devolvendo `ProblemDetail` com o status correto. Você herda isso e sobrescreve só o que quer mudar.

## 5.3 Anatomia do `GlobalExceptionHandler`

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // 1. Erros da aplicação: status vem da categoria do ErrorCode
    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> handleApplicationException(ApplicationException ex) { ... }

    // 1b. @Validated em beans de serviço
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) { ... }

    // 2. Erros do framework: sobrescrevemos só para formatar a lista de campos
    @Override protected ResponseEntity<Object> handleMethodArgumentNotValid(...) { ... }
    @Override protected ResponseEntity<Object> handleHandlerMethodValidationException(...) { ... }

    // 2b. Funil de TODAS as respostas da superclasse: adiciona code, correlationId, timestamp
    @Override protected ResponseEntity<Object> handleExceptionInternal(...) { ... }

    // 3. Rede de segurança: bug → 500 genérico, sem vazar mensagem
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) { ... }
}
```

### Como o Spring escolhe o handler

- Dentro de um advice, vence o `@ExceptionHandler` cujo tipo declarado é o **mais próximo** da
  exceção lançada na hierarquia. Por isso `handleUnexpected(Exception)` não "rouba" uma
  `NotFoundException`: `ApplicationException` está mais perto.
- Os handlers herdados de `ResponseEntityExceptionHandler` (para `ErrorResponseException`,
  `MethodArgumentNotValidException`...) também são mais específicos que `Exception`, então continuam valendo.
- Se nada casar com a exceção, o Spring tenta casar com as **causas** dela (exceção embrulhada).
- Handlers do controller vêm antes dos advices; entre advices, vale `@Order`.
- Um advice pode ser restrito: `@RestControllerAdvice(basePackages = "...")`, `assignableTypes`, `annotations`.

### ⚠️ Cuidado com o `@ExceptionHandler(Exception.class)`

Ele captura **tudo** que nenhum outro handler mais específico pegou, inclusive exceções que outras
partes do framework esperavam tratar. O caso clássico:

```java
@PreAuthorize("hasRole('ADMIN')")      // lança AccessDeniedException DENTRO do controller
@GetMapping("/admin/report") ...
```

Sem um handler específico, o `AccessDeniedException` cai no `Exception.class` e vira **500**. Se
você usa Spring Security com method security, adicione:

```java
@ExceptionHandler(AccessDeniedException.class)
ResponseEntity<ProblemDetail> forbidden(AccessDeniedException ex) { /* 403, code FORBIDDEN */ }

@ExceptionHandler(AuthenticationException.class)
ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException ex) { /* 401 */ }
```

## 5.4 `ErrorResponse` e `ErrorResponseException` (Spring 6+)

O Spring 6 introduziu `ErrorResponse`: um contrato para exceções que **sabem** virar resposta HTTP
(status, headers e um `ProblemDetail`). Todas as exceções do Spring MVC o implementam, e você pode
estender `ErrorResponseException`.

**Por que o projeto não usa isso nas exceções de domínio?** Pelo mesmo motivo do `@ResponseStatus`
(ver [capítulo 02](02-desenhando-excecoes-da-aplicacao.md#24-o-domínio-não-conhece-http)): seria
HTTP dentro do domínio. `ErrorResponseException` é ótima para exceções que **nascem** na camada web.

## 5.5 `spring.mvc.problemdetails.enabled`

Com `true`, o Spring Boot registra seu próprio `@ControllerAdvice` estendendo
`ResponseEntityExceptionHandler` (só se você não tiver um). É uma boa opção para um projeto que
ainda não tem tratamento customizado. Como o projeto já tem o seu, mantemos `false` no
[`application.yml`](../src/main/resources/application.yml).

## 5.6 O que o advice não alcança

### Filters e Spring Security

Exceções em filters não passam pelo `DispatcherServlet`. Opções:

**(a)** Tratar no próprio filter (ex.: `AuthenticationEntryPoint` e `AccessDeniedHandler` do Spring Security).

**(b)** Delegar para o mesmo `HandlerExceptionResolver` que alimenta o advice, reaproveitando todo o contrato:

```java
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver resolver;

    ProblemAuthenticationEntryPoint(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) {
        resolver.resolveException(req, res, null, ex);   // cai no @ExceptionHandler(AuthenticationException)
    }
}

// SecurityFilterChain
http.exceptionHandling(e -> e
        .authenticationEntryPoint(entryPoint)
        .accessDeniedHandler((req, res, ex) -> resolver.resolveException(req, res, null, ex)));
```

### `/error` e o `BasicErrorController`

Tudo que escapa (filter que falhou, erro de container) vai para `/error`. Por padrão o Boot devolve
`timestamp`, `status`, `error`, `path`. O projeto:

1. Configura `server.error.include-message/stacktrace/exception/binding-errors` para **nunca** expor
   detalhes ([`application.yml`](../src/main/resources/application.yml)).
2. Registra [`ProblemErrorAttributes`](../src/main/java/dev/cheatsheet/exceptions/shared/web/ProblemErrorAttributes.java),
   um `DefaultErrorAttributes` que acrescenta `code` e `correlationId`.
3. Faz o [`CorrelationIdFilter`](../src/main/java/dev/cheatsheet/exceptions/shared/web/CorrelationIdFilter.java)
   rodar também no dispatch de ERROR, reaproveitando o mesmo id.

Para uniformidade total, você pode também implementar seu próprio `ErrorController`.

> **Spring Boot 4:** as classes de erro do MVC mudaram de pacote, para
> `org.springframework.boot.webmvc.error` (`DefaultErrorAttributes`, `ErrorAttributes`) e
> `org.springframework.boot.webmvc.autoconfigure.error` (`BasicErrorController`).
> `ErrorAttributeOptions` está em `org.springframework.boot.web.error`.

### Resposta já enviada

Se a exceção ocorre depois que a resposta foi *committed* (streaming, corpo grande já enviado),
não há mais como mudar status nem corpo. O handler roda, mas o cliente vê uma conexão cortada.
Logue e siga.

## 5.7 Controller e service limpos

O resultado de centralizar é que o código de negócio fica livre de tratamento de erro:

```java
// OrderController: nenhum try/catch
@PostMapping("/{id}/payment")
OrderResponse pay(@PathVariable UUID id, @Valid @RequestBody PayOrderRequest request) {
    return OrderResponse.from(service.pay(id, request.paymentToken()));
}

// OrderService: lança com significado, nenhum HTTP
public Order get(UUID id) {
    return repository.findById(id)
            .orElseThrow(() -> new NotFoundException(OrderErrorCode.ORDER_NOT_FOUND, id));
}
```

## 5.8 WebFlux (nota rápida)

O modelo é o mesmo (`@RestControllerAdvice`, `ProblemDetail`), mas a base é
`org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler` e a
última linha de defesa é o `ErrorWebExceptionHandler`. Em código reativo, erro é **sinal** no fluxo
(`Mono.error(...)`, `onErrorResume`, `onErrorMap`): nunca `try/catch` em volta de um `Mono`.

---

### ✅ Resumo do capítulo

- Um `@RestControllerAdvice` estendendo `ResponseEntityExceptionHandler`; controllers e services sem try/catch.
- Sobrescreva `handleExceptionInternal` para que **todo** erro siga o mesmo contrato.
- O handler de `Exception` é a rede de segurança, mas cuidado com o que ele engole (`AccessDeniedException`).
- Filters e Security ficam fora do advice: trate lá ou delegue ao `HandlerExceptionResolver`.
- Configure `/error` para não vazar nada.
