package dev.cheatsheet.exceptions.payment;

import dev.cheatsheet.exceptions.payment.sdk.PaymentSdkException;
import dev.cheatsheet.exceptions.shared.error.BusinessRuleException;
import dev.cheatsheet.exceptions.shared.error.IntegrationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SdkPaymentGatewayTest {

    private final SdkPaymentGateway gateway = new SdkPaymentGateway();

    @Test
    void timeoutPreservaACausaOriginal() {
        var ex = catchThrowableOfType(IntegrationException.class, () -> gateway.charge("tok_timeout", BigDecimal.TEN));
        assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PROVIDER_TIMEOUT);
        assertThat(ex.getCause()).isInstanceOf(SocketTimeoutException.class);
        assertThat(ex.isRetryable()).isTrue();
    }

    @Test
    void indisponivelPropagaRetryAfter() {
        var ex = catchThrowableOfType(IntegrationException.class, () -> gateway.charge("tok_unavailable", BigDecimal.TEN));
        assertThat(ex.getRetryAfter()).contains(Duration.ofSeconds(30));
        assertThat(ex.getCause()).isInstanceOf(PaymentSdkException.class);
    }

    @Test
    void recusaViraRegraDeNegocio() {
        var ex = catchThrowableOfType(BusinessRuleException.class, () -> gateway.charge("tok_declined", BigDecimal.TEN));
        assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DECLINED);
    }
}
