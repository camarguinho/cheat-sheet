package dev.cheatsheet.exceptions.payment.sdk;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.UUID;

/**
 * "SDK de terceiro" determinístico para demonstração. O token define o comportamento:
 * <pre>
 *   tok_declined       → 402 card_declined
 *   tok_provider_error → 500 internal_error
 *   tok_bad_request    → 400 invalid_request (bug nosso ao chamar o provedor)
 *   tok_unavailable    → 503 maintenance, Retry-After 30s
 *   tok_timeout        → SocketTimeoutException
 *   qualquer outro     → sucesso
 * </pre>
 */
public class FakePaymentSdk {

    public String charge(String token, BigDecimal amount) throws PaymentSdkException, SocketTimeoutException {
        return switch (token) {
            case "tok_declined" -> throw new PaymentSdkException(402, "card_declined", "Your card was declined.", null);
            case "tok_provider_error" -> throw new PaymentSdkException(500, "internal_error", "Upstream failure id=8f2c", null);
            case "tok_bad_request" -> throw new PaymentSdkException(400, "invalid_request", "amount must have 2 decimals", null);
            case "tok_unavailable" -> throw new PaymentSdkException(503, "maintenance", "Scheduled maintenance", 30);
            case "tok_timeout" -> throw new SocketTimeoutException("Read timed out after 2000ms");
            default -> "pay_" + UUID.randomUUID();
        };
    }
}
