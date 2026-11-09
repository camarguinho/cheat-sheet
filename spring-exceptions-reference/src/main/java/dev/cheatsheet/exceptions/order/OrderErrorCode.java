package dev.cheatsheet.exceptions.order;

import dev.cheatsheet.exceptions.shared.error.ErrorCategory;
import dev.cheatsheet.exceptions.shared.error.ErrorCode;

/** Catálogo de erros do contexto de pedidos. Cada constante é contrato público da API. */
public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND(ErrorCategory.NOT_FOUND,
            "Pedido não encontrado",
            "O pedido {0} não foi encontrado."),

    ORDER_TOTAL_LIMIT_EXCEEDED(ErrorCategory.BUSINESS_RULE,
            "Limite do pedido excedido",
            "O total do pedido ({0}) excede o limite permitido de {1}."),

    ORDER_INVALID_STATE(ErrorCategory.CONFLICT,
            "Operação incompatível com o estado do pedido",
            "Não é possível {2} o pedido {0}: o status atual é {1}.");

    private final ErrorCategory category;
    private final String title;
    private final String messageTemplate;

    OrderErrorCode(ErrorCategory category, String title, String messageTemplate) {
        this.category = category;
        this.title = title;
        this.messageTemplate = messageTemplate;
    }

    @Override public String code() { return name(); }
    @Override public ErrorCategory category() { return category; }
    @Override public String title() { return title; }
    @Override public String messageTemplate() { return messageTemplate; }
}
