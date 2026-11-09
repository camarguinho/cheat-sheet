package dev.cheatsheet.exceptions.shared;

import dev.cheatsheet.exceptions.catalog.ErrorCatalog;
import dev.cheatsheet.exceptions.order.OrderErrorCode;
import dev.cheatsheet.exceptions.payment.PaymentErrorCode;
import dev.cheatsheet.exceptions.shared.error.BusinessRuleException;
import dev.cheatsheet.exceptions.shared.error.CommonErrorCode;
import dev.cheatsheet.exceptions.shared.error.ErrorCategory;
import dev.cheatsheet.exceptions.shared.error.ErrorCode;
import dev.cheatsheet.exceptions.shared.error.IntegrationException;
import dev.cheatsheet.exceptions.shared.error.NotFoundException;
import dev.cheatsheet.exceptions.shared.web.HttpStatusMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ErrorModelTest {

    private static List<ErrorCode> allCodes() {
        List<ErrorCode> codes = new ArrayList<>();
        codes.addAll(List.of(CommonErrorCode.values()));
        codes.addAll(List.of(OrderErrorCode.values()));
        codes.addAll(List.of(PaymentErrorCode.values()));
        return codes;
    }

    @Test
    void catalogoAceitaOsCodigosAtuais() {
        assertThat(new ErrorCatalog(allCodes()).all()).hasSize(allCodes().size());
    }

    @Test
    void catalogoRecusaCodigoDuplicado() {
        ErrorCode clone = new ErrorCode() {
            public String code() { return "ORDER_NOT_FOUND"; }
            public ErrorCategory category() { return ErrorCategory.NOT_FOUND; }
            public String title() { return "x"; }
            public String messageTemplate() { return "x"; }
        };
        List<ErrorCode> codes = allCodes();
        codes.add(clone);
        assertThatIllegalStateException().isThrownBy(() -> new ErrorCatalog(codes)).withMessageContaining("duplicado");
    }

    @ParameterizedTest
    @EnumSource(ErrorCategory.class)
    void todaCategoriaTemStatusCoerente(ErrorCategory category) {
        var status = HttpStatusMapper.toStatus(category);
        assertThat(status.is4xxClientError()).isEqualTo(category.isClientError());
    }

    @Test
    void mensagemEhFormatadaComArgumentos() {
        var ex = new NotFoundException(OrderErrorCode.ORDER_NOT_FOUND, "123");
        assertThat(ex.getMessage()).isEqualTo("O pedido 123 não foi encontrado.");
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void subclasseRecusaCodigoDeCategoriaIncompativel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NotFoundException(OrderErrorCode.ORDER_TOTAL_LIMIT_EXCEEDED))
                .withMessageContaining("BUSINESS_RULE");
    }

    @Test
    void detalhesNaoPodemSobrescreverCamposDoContrato() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BusinessRuleException(OrderErrorCode.ORDER_TOTAL_LIMIT_EXCEEDED, Map.of("status", 200)));
    }

    @Test
    void integrationExceptionExigeCausa() {
        assertThatNullPointerException()
                .isThrownBy(() -> new IntegrationException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR, null));
    }
}
