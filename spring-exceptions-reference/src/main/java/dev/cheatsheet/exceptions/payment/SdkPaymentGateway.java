package dev.cheatsheet.exceptions.payment;

import dev.cheatsheet.exceptions.payment.sdk.FakePaymentSdk;
import dev.cheatsheet.exceptions.payment.sdk.PaymentSdkException;
import dev.cheatsheet.exceptions.shared.error.BusinessRuleException;
import dev.cheatsheet.exceptions.shared.error.IntegrationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Adaptador (anti-corruption layer): o ÚNICO lugar que conhece as exceções do SDK.
 * Traduz o vocabulário do provedor para o da aplicação e sempre preserva a causa.
 *
 * <p>Ponto sutil: um 4xx do provedor para nós (ex.: 400 invalid_request) <b>não</b> é erro do
 * nosso cliente; é bug nosso na integração. Por isso vira 502, não 400 (ver docs/04).
 */
@Component
class SdkPaymentGateway implements PaymentGateway {

    private final FakePaymentSdk sdk = new FakePaymentSdk();

    @Override
    public String charge(String paymentToken, BigDecimal amount) {
        try {
            return sdk.charge(paymentToken, amount);
        } catch (SocketTimeoutException e) {
            throw new IntegrationException(PaymentErrorCode.PAYMENT_PROVIDER_TIMEOUT, e);
        } catch (PaymentSdkException e) {
            throw translate(e);
        }
    }

    private RuntimeException translate(PaymentSdkException e) {
        if (e.httpStatus() == 402 || "card_declined".equals(e.providerCode())) {
            return new BusinessRuleException(PaymentErrorCode.PAYMENT_DECLINED);
        }
        if (e.httpStatus() == 503) {
            Duration retryAfter = e.retryAfterSeconds() == null ? null : Duration.ofSeconds(e.retryAfterSeconds());
            return new IntegrationException(PaymentErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, e, retryAfter);
        }
        return new IntegrationException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR, e);
    }
}
