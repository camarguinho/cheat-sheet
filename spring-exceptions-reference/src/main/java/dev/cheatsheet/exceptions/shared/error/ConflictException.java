package dev.cheatsheet.exceptions.shared.error;

import java.util.Map;

/** Erro esperado: conflito com o estado atual do recurso (409). */
public class ConflictException extends ApplicationException {

    public ConflictException(ErrorCode code, Object... args) {
        this(code, Map.of(), args);
    }

    public ConflictException(ErrorCode code, Map<String, ?> details, Object... args) {
        super(requireCategory(code, ErrorCategory.CONFLICT), details, null, args);
    }
}
