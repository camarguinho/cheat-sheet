package dev.cheatsheet.exceptions.shared.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Raiz de todas as exceções <b>esperadas</b> da aplicação: falhas que fazem parte do
 * contrato (pedido não existe, limite excedido, provedor fora do ar).
 *
 * <p>Decisões de design:
 * <ul>
 *   <li><b>Unchecked</b>: o caller não é obrigado a tratar; quem sabe o que fazer captura,
 *       o resto propaga até o handler global (ver docs/02).</li>
 *   <li><b>Carrega um {@link ErrorCode}</b>, não um HTTP status: o domínio não conhece HTTP.</li>
 *   <li><b>Subclasses por categoria</b> ({@link NotFoundException}, {@link BusinessRuleException}...)
 *       existem para leitura e para {@code catch} seletivo; o código continua sendo a
 *       fonte da verdade, e o construtor recusa combinações incoerentes (fail-fast).</li>
 *   <li><b>{@code details}</b> é um mapa de dados estruturados <i>seguros para o cliente</i>
 *       (ex.: limite e valor tentado), serializado como extensão do Problem Detail.</li>
 * </ul>
 */
public abstract class ApplicationException extends RuntimeException {

    /** Nomes usados pelo contrato de erro; um detalhe com esses nomes sobrescreveria campos padrão. */
    public static final Set<String> RESERVED_DETAIL_KEYS = Set.of(
            "type", "title", "status", "detail", "instance", "code", "correlationId", "timestamp", "errors", "retryable");

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> details;

    protected ApplicationException(ErrorCode errorCode, Map<String, ?> details, Throwable cause, Object... args) {
        super(Objects.requireNonNull(errorCode, "errorCode").format(args), cause);
        this.errorCode = errorCode;
        if (details != null) {
            details.keySet().stream().filter(RESERVED_DETAIL_KEYS::contains).findFirst().ifPresent(key -> {
                throw new IllegalArgumentException("Chave reservada em details: " + key);
            });
        }
        this.details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /** Garante que a subclasse só seja usada com códigos da categoria compatível. */
    protected static ErrorCode requireCategory(ErrorCode code, ErrorCategory... allowed) {
        Objects.requireNonNull(code, "errorCode");
        for (ErrorCategory category : allowed) {
            if (code.category() == category) {
                return code;
            }
        }
        throw new IllegalArgumentException(
                "Código %s (categoria %s) não pode ser usado aqui; esperado: %s"
                        .formatted(code.code(), code.category(), java.util.Arrays.toString(allowed)));
    }
}
