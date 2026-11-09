package dev.cheatsheet.exceptions.payment;

import dev.cheatsheet.exceptions.shared.error.ErrorCategory;
import dev.cheatsheet.exceptions.shared.error.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {

    /** Recusa é resposta de negócio legítima do provedor, não falha técnica. */
    PAYMENT_DECLINED(ErrorCategory.BUSINESS_RULE,
            "Pagamento recusado",
            "O pagamento foi recusado pelo emissor. Tente outro meio de pagamento."),

    /** O provedor respondeu com erro (5xx, ou 4xx causado por bug nosso). */
    PAYMENT_PROVIDER_ERROR(ErrorCategory.DEPENDENCY_FAILURE,
            "Falha no provedor de pagamento",
            "O provedor de pagamento retornou um erro inesperado."),

    PAYMENT_PROVIDER_TIMEOUT(ErrorCategory.DEPENDENCY_TIMEOUT,
            "Provedor de pagamento não respondeu",
            "O provedor de pagamento não respondeu a tempo. O pagamento pode ou não ter sido processado."),

    PAYMENT_PROVIDER_UNAVAILABLE(ErrorCategory.UNAVAILABLE,
            "Provedor de pagamento indisponível",
            "O provedor de pagamento está temporariamente indisponível. Tente novamente mais tarde.");

    private final ErrorCategory category;
    private final String title;
    private final String messageTemplate;

    PaymentErrorCode(ErrorCategory category, String title, String messageTemplate) {
        this.category = category;
        this.title = title;
        this.messageTemplate = messageTemplate;
    }

    @Override public String code() { return name(); }
    @Override public ErrorCategory category() { return category; }
    @Override public String title() { return title; }
    @Override public String messageTemplate() { return messageTemplate; }
}
