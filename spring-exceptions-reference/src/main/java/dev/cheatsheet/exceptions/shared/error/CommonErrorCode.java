package dev.cheatsheet.exceptions.shared.error;

/** Códigos transversais, que não pertencem a nenhum contexto de negócio específico. */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(ErrorCategory.VALIDATION,
            "Requisição inválida",
            "Um ou mais campos são inválidos. Veja a lista em 'errors'."),

    MALFORMED_REQUEST(ErrorCategory.VALIDATION,
            "Requisição malformada",
            "O corpo ou os parâmetros da requisição não puderam ser interpretados."),

    RESOURCE_NOT_FOUND(ErrorCategory.NOT_FOUND,
            "Recurso não encontrado",
            "O recurso ''{0}'' não foi encontrado."),

    INTERNAL_ERROR(ErrorCategory.INTERNAL,
            "Erro interno",
            "Ocorreu um erro inesperado. Informe o correlationId ao suporte.");

    private final ErrorCategory category;
    private final String title;
    private final String messageTemplate;

    CommonErrorCode(ErrorCategory category, String title, String messageTemplate) {
        this.category = category;
        this.title = title;
        this.messageTemplate = messageTemplate;
    }

    @Override public String code() { return name(); }
    @Override public ErrorCategory category() { return category; }
    @Override public String title() { return title; }
    @Override public String messageTemplate() { return messageTemplate; }
}
