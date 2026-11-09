package dev.cheatsheet.exceptions.shared.error;

/**
 * Classifica um erro pelo seu <b>significado</b>, não pelo protocolo.
 *
 * <p>O domínio fala em categorias ("não encontrado", "regra violada"); quem traduz
 * categoria para HTTP status é a camada web ({@code HttpStatusMapper}). Assim o mesmo
 * erro pode virar 404 numa API REST, {@code NOT_FOUND} num gRPC ou um evento de falha
 * numa fila, sem o domínio conhecer nenhum desses protocolos.
 */
public enum ErrorCategory {

    /** Entrada do cliente inválida (formato, campo obrigatório, faixa). Corrigível pelo cliente. */
    VALIDATION,

    /** O recurso identificado não existe (ou o cliente não pode saber que existe). */
    NOT_FOUND,

    /** O pedido é válido, mas conflita com o estado atual do recurso (transição de estado, versão). */
    CONFLICT,

    /** O pedido é bem formado, mas viola uma regra de negócio. */
    BUSINESS_RULE,

    /** Falta identificar quem chama. */
    UNAUTHENTICATED,

    /** Quem chama foi identificado, mas não tem permissão. */
    FORBIDDEN,

    /** Limite de taxa/cota atingido. */
    RATE_LIMITED,

    /** Uma dependência externa respondeu com erro ou resposta inválida. */
    DEPENDENCY_FAILURE,

    /** Uma dependência externa não respondeu a tempo. */
    DEPENDENCY_TIMEOUT,

    /** O próprio serviço (ou uma dependência essencial) está temporariamente indisponível. */
    UNAVAILABLE,

    /** Bug ou falha inesperada. Nunca é culpa do cliente. */
    INTERNAL;

    /** Erros cuja repetição, sem mudança na requisição, pode dar certo. */
    public boolean isTransient() {
        return this == DEPENDENCY_TIMEOUT || this == UNAVAILABLE || this == RATE_LIMITED;
    }

    /** Erros causados pelo cliente (equivalem à família 4xx). */
    public boolean isClientError() {
        return switch (this) {
            case VALIDATION, NOT_FOUND, CONFLICT, BUSINESS_RULE,
                 UNAUTHENTICATED, FORBIDDEN, RATE_LIMITED -> true;
            default -> false;
        };
    }
}
