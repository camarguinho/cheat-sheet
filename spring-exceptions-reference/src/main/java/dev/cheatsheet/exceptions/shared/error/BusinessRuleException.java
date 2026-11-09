package dev.cheatsheet.exceptions.shared.error;

import java.util.Map;

/** Erro esperado: regra de negócio violada (422). */
public class BusinessRuleException extends ApplicationException {

    public BusinessRuleException(ErrorCode code, Object... args) {
        this(code, Map.of(), args);
    }

    public BusinessRuleException(ErrorCode code, Map<String, ?> details, Object... args) {
        super(requireCategory(code, ErrorCategory.BUSINESS_RULE), details, null, args);
    }
}
