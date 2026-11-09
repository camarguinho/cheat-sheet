package dev.cheatsheet.exceptions.shared;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bugs viram 500 genérico, sem vazar a mensagem interna. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(UnexpectedErrorTest.FailingEndpoints.class)
class UnexpectedErrorTest {

    @Autowired
    MockMvc mvc;

    @Test
    void excecaoInesperada_500_semVazarDetalhes() throws Exception {
        mvc.perform(get("/test-support/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void invarianteDeDominioQuebrada_500_porqueEhBug() throws Exception {
        mvc.perform(get("/test-support/invariant"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void responseStatusException_tambemSegueOContrato() throws Exception {
        mvc.perform(get("/test-support/gone"))
                .andExpect(status().is(410))
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @TestConfiguration
    static class FailingEndpoints {
        @Bean
        BoomController boomController() {
            return new BoomController();
        }
    }

    @RestController
    static class BoomController {
        @GetMapping("/test-support/boom")
        String boom() {
            throw new IllegalStateException("SELECT * FROM customers WHERE cpf = '123' failed");
        }

        @GetMapping("/test-support/invariant")
        String invariant() {
            throw new IllegalArgumentException("quantity deve ser positiva: 0");
        }

        @GetMapping("/test-support/gone")
        String gone() {
            throw new ResponseStatusException(HttpStatus.GONE, "recurso removido");
        }
    }
}
