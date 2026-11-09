# 07 — Validação: onde cada erro nasce

[← Problem Details](06-problem-details-rfc-9457.md) · [Índice](README.md) · [Próximo: Integrações, transações e async →](08-integracoes-transacoes-e-async.md)

---

## 7.1 Três camadas, três tipos de erro

| Camada | O que valida | Mecanismo | Erro | Status |
|---|---|---|---|---|
| **Borda** (DTO) | Forma: obrigatório, tamanho, faixa, formato | Bean Validation (`@NotBlank`, `@Size`...) | `MethodArgumentNotValidException` / `HandlerMethodValidationException` | **400** |
| **Domínio** (entidade/VO) | Invariantes: um `OrderItem` nunca tem quantidade ≤ 0 | Construtor + exceções do JDK | `IllegalArgumentException` | **500** se escapar (é bug) |
| **Caso de uso** (service) | Regras que dependem de estado/dados: limite, transição, saldo | `if` + `ApplicationException` | `BusinessRuleException`, `ConflictException` | **422 / 409** |

Por que a invariante do domínio vira **500**? Porque ela é a **segunda** barreira. Se a borda
validou direito, ela nunca dispara a partir de uma requisição. Se disparou, a borda tem um buraco:
é bug nosso, não erro do cliente. Tratar como 400 esconderia o bug.

```java
// Borda — OrderDtos.ItemRequest
@NotNull @Positive @Max(1000) Integer quantity

// Domínio — OrderItem (segunda barreira, independente de framework)
if (quantity <= 0) throw new IllegalArgumentException("quantity deve ser positiva: " + quantity);

// Caso de uso — OrderService
if (total.compareTo(MAX_ORDER_TOTAL) > 0)
    throw new BusinessRuleException(ORDER_TOTAL_LIMIT_EXCEEDED, Map.of("total", total, "limit", MAX_ORDER_TOTAL), total, MAX_ORDER_TOTAL);
```

## 7.2 As exceções de validação do Spring

| Exceção | Quando | Tratada por |
|---|---|---|
| `MethodArgumentNotValidException` | `@Valid @RequestBody` inválido | `handleMethodArgumentNotValid` |
| `HandlerMethodValidationException` | Constraint direto em parâmetro do controller (`@RequestParam @Size`), Spring 6.1+ | `handleHandlerMethodValidationException` |
| `ConstraintViolationException` (jakarta) | `@Validated` em bean que não é controller (ex.: service) | `@ExceptionHandler` próprio |
| `HttpMessageNotReadableException` | JSON malformado, tipo errado no corpo | herdado → 400 `MALFORMED_REQUEST` |
| `MethodArgumentTypeMismatchException` | `"abc"` onde se espera `UUID`/`int` no path/query | herdado → 400 `MALFORMED_REQUEST` |
| `MissingServletRequestParameterException` | `@RequestParam` obrigatório ausente | herdado → 400 |

### Validação de método embutida (Spring 6.1+)

Desde o Spring 6.1, se um método de controller tem constraints **diretamente** em parâmetros, o
Spring aplica validação de método sem precisar de `@Validated` na classe:

```java
@GetMapping
List<OrderResponse> listByCustomer(@RequestParam @Size(min = 3, max = 40) String customerId) { ... }
```

Erro → `HandlerMethodValidationException`. Atenção: nesse modo, um `@Valid @RequestBody` no
**mesmo** método também passa a reportar via `HandlerMethodValidationException`, e não via
`MethodArgumentNotValidException`. Por isso o projeto trata as duas, gerando a mesma lista `errors`.

## 7.3 Formato dos erros de campo

```json
"errors": [
  { "field": "customerId",        "message": "must not be blank" },
  { "field": "items[0].quantity", "message": "must be greater than 0" }
]
```

Decisões:

- **Todos os erros de uma vez**, não só o primeiro: o cliente corrige tudo numa ida.
- **Ordenados** por campo: resposta determinística, testes estáveis.
- **Caminho completo** (`items[0].quantity`) para coleções aninhadas.
- **Sem `rejectedValue`**: ecoar o valor pode expor senha, CPF ou cartão, e pode ser usado para
  refletir conteúdo malicioso. O cliente já sabe o que enviou.

## 7.4 Mensagens de validação

As mensagens padrão vêm do Hibernate Validator, no idioma do `Locale` da requisição/JVM
("must not be blank"). Para controlar:

```java
@NotBlank(message = "{order.customerId.required}")
String customerId;
```

```properties
# src/main/resources/ValidationMessages.properties (ou messages.properties com MessageSource)
order.customerId.required=Informe o cliente.
```

Se o cliente precisa reagir programaticamente a cada campo, considere expor também a **constraint**
(`"constraint": "NotBlank"`) na lista, que é estável, ao contrário do texto.

## 7.5 Validações que cruzam campos

Use uma constraint de classe (`@ValidPeriod` em um record com `start` e `end`). O erro aparece como
*global error* do `BindingResult`; o handler do projeto o inclui com `field` = nome do objeto.

## 7.6 Anti-patterns

- ❌ Validar só no domínio e devolver 500 para entrada inválida.
- ❌ Validar só na borda e confiar que o domínio "nunca recebe lixo" (outros adaptadores: fila, batch).
- ❌ Regra de negócio que depende de estado como Bean Validation com acesso a banco
  (`@UniqueEmail` consultando repositório): mistura camadas e tem condição de corrida. Faça no
  caso de uso e, em última instância, deixe a constraint do banco decidir (→ 409).
- ❌ 422 para campo obrigatório ausente (é 400 na convenção do projeto).

---

### ✅ Resumo do capítulo

- Borda: Bean Validation → 400 com lista de campos.
- Domínio: invariantes com exceções do JDK → 500 se escapar (bug).
- Caso de uso: regra de negócio → 422; conflito de estado → 409.
- Trate `MethodArgumentNotValidException` **e** `HandlerMethodValidationException`.
- Não ecoe valores rejeitados.
