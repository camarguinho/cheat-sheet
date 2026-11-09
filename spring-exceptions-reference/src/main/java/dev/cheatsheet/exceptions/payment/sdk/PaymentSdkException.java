package dev.cheatsheet.exceptions.payment.sdk;

/**
 * Simula a exceção <b>checked</b> de um SDK de terceiro, com o vocabulário do provedor.
 * É exatamente o tipo de exceção que não pode vazar para o domínio nem para o cliente.
 */
public class PaymentSdkException extends Exception {

    private final int httpStatus;
    private final String providerCode;
    private final Integer retryAfterSeconds;

    public PaymentSdkException(int httpStatus, String providerCode, String message, Integer retryAfterSeconds) {
        super(message);
        this.httpStatus = httpStatus;
        this.providerCode = providerCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int httpStatus() { return httpStatus; }
    public String providerCode() { return providerCode; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
