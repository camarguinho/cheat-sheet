package dev.cheatsheet.exceptions.shared.error;

import java.util.Map;

/** Erro esperado: recurso que não existe (404). */
public class NotFoundException extends ApplicationException {

    public NotFoundException(ErrorCode code, Object... args) {
        this(code, Map.of(), args);
    }

    public NotFoundException(ErrorCode code, Map<String, ?> details, Object... args) {
        super(requireCategory(code, ErrorCategory.NOT_FOUND), details, null, args);
    }
}
