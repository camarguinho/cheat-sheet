# 10 — Testando erros

[← Logging](09-logging-e-observabilidade.md) · [Índice](README.md) · [Próximo: Checklist →](11-checklist-e-anti-patterns.md)

---

O formato de erro é **API pública**, tanto quanto o caminho feliz. Na prática, é a parte que mais
quebra clientes sem ninguém perceber, porque raramente é testada.

## 10.1 O que testar, em que nível

| Teste | Nível | Arquivo |
|---|---|---|
| Contrato HTTP de cada erro (status, content-type, campos obrigatórios) | Integração, MockMvc | [`OrderApiErrorContractTest`](../src/test/java/dev/cheatsheet/exceptions/order/OrderApiErrorContractTest.java) |
| Bug vira 500 sem vazar detalhe | Integração, MockMvc | [`UnexpectedErrorTest`](../src/test/java/dev/cheatsheet/exceptions/shared/UnexpectedErrorTest.java) |
| Domínio lança o **code** certo | Unitário | [`OrderTest`](../src/test/java/dev/cheatsheet/exceptions/order/OrderTest.java) |
| Adaptador traduz e **preserva a causa** | Unitário | [`SdkPaymentGatewayTest`](../src/test/java/dev/cheatsheet/exceptions/payment/SdkPaymentGatewayTest.java) |
| Catálogo consistente; mapeamento categoria→status coerente | Unitário | [`ErrorModelTest`](../src/test/java/dev/cheatsheet/exceptions/shared/ErrorModelTest.java) |

## 10.2 Uma asserção de contrato reutilizável

```java
private static void assertProblemContract(ResultActions result, int status, String code) throws Exception {
    result.andExpect(status().is(status))
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.status").value(status))
          .andExpect(jsonPath("$.code").value(code))
          .andExpect(jsonPath("$.title").value(notNullValue()))
          .andExpect(jsonPath("$.instance").value(notNullValue()))
          .andExpect(jsonPath("$.timestamp").value(notNullValue()))
          .andExpect(jsonPath("$.correlationId").value(notNullValue()))
          .andExpect(header().exists("X-Correlation-Id"));
}

@Test
void pedidoInexistente_404() throws Exception {
    assertProblemContract(mvc.perform(get("/orders/{id}", UUID.randomUUID())), 404, "ORDER_NOT_FOUND");
}
```

Cada cenário fica com uma linha, e qualquer desvio do contrato aparece em todos ao mesmo tempo.

## 10.3 Teste o code, não a mensagem

```java
// ❌ frágil: quebra ao corrigir uma vírgula
assertThatThrownBy(order::cancel).hasMessage("Não é possível cancelar o pedido ...");

// ✅ estável
ConflictException ex = catchThrowableOfType(ConflictException.class, order::cancel);
assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATE);
assertThat(ex.getDetails()).containsEntry("currentStatus", OrderStatus.PAID);
```

Teste a mensagem em **um** lugar (o template formata direito), não em todo teste de regra.

## 10.4 Teste o que **não** pode aparecer

```java
.andExpect(content().string(not(containsString("SELECT"))))
.andExpect(content().string(not(containsString("IllegalStateException"))))
.andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
```

Testes negativos são a única forma de garantir que um refactor no handler não começa a vazar detalhe interno.

## 10.5 Teste a ordem dos efeitos

`pagarPedidoCancelado_409_semChamarOProvedor` usa o token `tok_timeout`, que causaria 504 se o
provedor fosse chamado. Receber 409 prova que a validação aconteceu **antes** do efeito externo.

## 10.6 Endpoints de teste para o caminho inesperado

Para testar o 500 sem colocar um endpoint "quebra" na aplicação, o teste registra um controller só
no contexto de teste:

```java
@SpringBootTest
@AutoConfigureMockMvc
@Import(UnexpectedErrorTest.FailingEndpoints.class)
class UnexpectedErrorTest {

    @TestConfiguration
    static class FailingEndpoints {
        @Bean BoomController boomController() { return new BoomController(); }
    }

    @RestController
    static class BoomController {
        @GetMapping("/test-support/boom")
        String boom() { throw new IllegalStateException("SELECT * FROM customers WHERE cpf = '123' failed"); }
    }
}
```

## 10.7 Limites do MockMvc

- MockMvc **não** faz o forward para `/error` do container. Erros em filters/`BasicErrorController`
  precisam de teste com servidor real (`@SpringBootTest(webEnvironment = RANDOM_PORT)`).
- Filters registrados como bean **são** aplicados no MockMvc do `@AutoConfigureMockMvc` (por isso
  o `correlationId` aparece nos testes).

> **Spring Boot 4:** `@AutoConfigureMockMvc` está em
> `org.springframework.boot.webmvc.test.autoconfigure` e vem do starter `spring-boot-starter-webmvc-test`.

---

### ✅ Resumo do capítulo

- Formato de erro é contrato: teste cada cenário com uma asserção comum.
- Teste o code, não o texto.
- Teste o que não pode vazar.
- Teste a ordem (validação antes de efeito colateral) e a preservação da causa.
