package dev.cheatsheet.exceptions.shared.error;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Falha ao falar com um sistema externo, já <b>traduzida</b> para a linguagem da aplicação.
 *
 * <p>A causa original (exceção do SDK, timeout de socket...) é sempre preservada em
 * {@link #getCause()} para o log; ela nunca chega ao cliente.
 */
public class IntegrationException extends ApplicationException {

    private final transient Duration retryAfter;

    public IntegrationException(ErrorCode code, Throwable cause, Object... args) {
        this(code, cause, null, args);
    }

    public IntegrationException(ErrorCode code, Throwable cause, Duration retryAfter, Object... args) {
        super(requireCategory(code, ErrorCategory.DEPENDENCY_FAILURE,
                        ErrorCategory.DEPENDENCY_TIMEOUT, ErrorCategory.UNAVAILABLE),
                Map.of(), Objects.requireNonNull(cause, "cause é obrigatória: não perca a stack trace original"),
                args);
        this.retryAfter = retryAfter;
    }

    /** Sugestão de espera antes de repetir, quando a dependência informou ou quando sabemos estimar. */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public boolean isRetryable() {
        return getErrorCode().category().isTransient();
    }
}
