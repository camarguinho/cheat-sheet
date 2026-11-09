package dev.cheatsheet.exceptions.order;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de CONTRATO do erro: o formato da resposta é API pública tanto quanto o caminho feliz.
 * Se um destes testes quebrar, algum cliente provavelmente quebra junto.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiErrorContractTest {

    private static final String VALID_ORDER = """
            {"customerId":"cust-1","items":[{"sku":"SKU-1","quantity":2,"unitPrice":10.50}]}
            """;

    @Autowired
    MockMvc mvc;

    private ResultActions postJson(String uri, String json) throws Exception {
        return mvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String createOrder() throws Exception {
        String body = postJson("/orders", VALID_ORDER)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Campos que TODA resposta de erro precisa ter. */
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

    @Nested
    class ErrosDaAplicacao {

        @Test
        void pedidoInexistente_404() throws Exception {
            UUID id = UUID.randomUUID();
            var result = mvc.perform(get("/orders/{id}", id));
            assertProblemContract(result, 404, "ORDER_NOT_FOUND");
            result.andExpect(jsonPath("$.type").value("/errors/ORDER_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("O pedido " + id + " não foi encontrado."))
                    .andExpect(jsonPath("$.instance").value("/orders/" + id));
        }

        @Test
        void limiteExcedido_422_comDetalhesEstruturados() throws Exception {
            var result = postJson("/orders", """
                    {"customerId":"cust-1","items":[{"sku":"TV","quantity":3,"unitPrice":5000.00}]}
                    """);
            assertProblemContract(result, 422, "ORDER_TOTAL_LIMIT_EXCEEDED");
            result.andExpect(jsonPath("$.detail").value("O total do pedido (15000.00) excede o limite permitido de 10000.00."))
                    .andExpect(jsonPath("$.limit").value(10000.00))
                    .andExpect(jsonPath("$.total").value(15000.00));
        }

        @Test
        void cancelarDuasVezes_409() throws Exception {
            String id = createOrder();
            mvc.perform(post("/orders/{id}/cancel", id)).andExpect(status().isOk());

            var result = mvc.perform(post("/orders/{id}/cancel", id));
            assertProblemContract(result, 409, "ORDER_INVALID_STATE");
            result.andExpect(jsonPath("$.currentStatus").value("CANCELLED"))
                    .andExpect(jsonPath("$.action").value("cancelar"));
        }

        @Test
        void pagarPedidoCancelado_409_semChamarOProvedor() throws Exception {
            String id = createOrder();
            mvc.perform(post("/orders/{id}/cancel", id)).andExpect(status().isOk());
            // tok_timeout faria 504 se o provedor fosse chamado; 409 prova que falhamos antes.
            assertProblemContract(postJson("/orders/" + id + "/payment", "{\"paymentToken\":\"tok_timeout\"}"),
                    409, "ORDER_INVALID_STATE");
        }

        @Test
        void correlationIdRecebidoEhPropagado() throws Exception {
            mvc.perform(get("/orders/{id}", UUID.randomUUID()).header("X-Correlation-Id", "abc-123"))
                    .andExpect(header().string("X-Correlation-Id", "abc-123"))
                    .andExpect(jsonPath("$.correlationId").value("abc-123"));
        }

        @Test
        void correlationIdMaliciosoEhDescartado() throws Exception {
            mvc.perform(get("/orders/{id}", UUID.randomUUID()).header("X-Correlation-Id", "x\nFAKE LOG LINE"))
                    .andExpect(jsonPath("$.correlationId").value(not(containsString("FAKE"))));
        }
    }

    @Nested
    class ErrosDeIntegracao {

        private ResultActions pay(String token) throws Exception {
            return postJson("/orders/" + createOrder() + "/payment", "{\"paymentToken\":\"" + token + "\"}");
        }

        @Test
        void pagamentoAprovado_200() throws Exception {
            pay("tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        }

        @Test
        void recusado_422_ehNegocioNaoFalhaTecnica() throws Exception {
            assertProblemContract(pay("tok_declined"), 422, "PAYMENT_DECLINED");
        }

        @Test
        void provedorComErro_502_naoVazaMensagemDoProvedor() throws Exception {
            var result = pay("tok_provider_error");
            assertProblemContract(result, 502, "PAYMENT_PROVIDER_ERROR");
            result.andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(content().string(not(containsString("8f2c"))));
        }

        @Test
        void provedorDevolve400_paraNosEh502_naoErroDoCliente() throws Exception {
            assertProblemContract(pay("tok_bad_request"), 502, "PAYMENT_PROVIDER_ERROR");
        }

        @Test
        void provedorIndisponivel_503_comRetryAfter() throws Exception {
            var result = pay("tok_unavailable");
            assertProblemContract(result, 503, "PAYMENT_PROVIDER_UNAVAILABLE");
            result.andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.retryable").value(true));
        }

        @Test
        void timeout_504() throws Exception {
            var result = pay("tok_timeout");
            assertProblemContract(result, 504, "PAYMENT_PROVIDER_TIMEOUT");
            result.andExpect(jsonPath("$.retryable").value(true));
        }
    }

    @Nested
    class ErrosDeValidacao {

        @Test
        void corpoInvalido_400_comListaDeCampos() throws Exception {
            var result = postJson("/orders", """
                    {"customerId":"","items":[{"sku":"A","quantity":0,"unitPrice":-1}]}
                    """);
            assertProblemContract(result, 400, "VALIDATION_FAILED");
            result.andExpect(jsonPath("$.type").value("/errors/VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors", hasSize(3)))
                    .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                    .andExpect(jsonPath("$.errors[1].field").value("items[0].quantity"))
                    .andExpect(jsonPath("$.errors[2].field").value("items[0].unitPrice"))
                    // o valor rejeitado não é ecoado
                    .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
        }

        @Test
        void parametroInvalido_400_viaValidacaoDeMetodo() throws Exception {
            var result = mvc.perform(get("/orders").param("customerId", "x"));
            assertProblemContract(result, 400, "VALIDATION_FAILED");
            result.andExpect(jsonPath("$.errors[0].field").value("customerId"));
        }

        @Test
        void jsonMalformado_400_semVazarMensagemDoParser() throws Exception {
            var result = postJson("/orders", "{\"customerId\": ");
            assertProblemContract(result, 400, "MALFORMED_REQUEST");
            result.andExpect(content().string(not(containsString("jackson"))))
                    .andExpect(content().string(not(containsString("Unexpected end"))));
        }

        @Test
        void idQueNaoEhUuid_400() throws Exception {
            assertProblemContract(mvc.perform(get("/orders/not-a-uuid")), 400, "MALFORMED_REQUEST");
        }
    }

    @Nested
    class ErrosDeProtocolo {

        @Test
        void rotaInexistente_404() throws Exception {
            assertProblemContract(mvc.perform(get("/nao-existe")), 404, "ROUTE_NOT_FOUND");
        }

        @Test
        void metodoNaoSuportado_405() throws Exception {
            assertProblemContract(mvc.perform(delete("/orders/{id}", UUID.randomUUID())), 405, "METHOD_NOT_ALLOWED");
        }

        @Test
        void mediaTypeNaoSuportado_415() throws Exception {
            var result = mvc.perform(post("/orders").contentType(MediaType.TEXT_PLAIN).content("oi"));
            assertProblemContract(result, 415, "UNSUPPORTED_MEDIA_TYPE");
        }
    }

    @Nested
    class CatalogoDeErros {

        @Test
        void typeDoProblemEhDesreferenciavel() throws Exception {
            mvc.perform(get("/errors/ORDER_NOT_FOUND"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.httpStatus").value(404))
                    .andExpect(jsonPath("$.category").value("NOT_FOUND"));
        }

        @Test
        void codigoDesconhecido_404() throws Exception {
            assertProblemContract(mvc.perform(get("/errors/NAO_EXISTE")), 404, "RESOURCE_NOT_FOUND");
        }
    }
}
