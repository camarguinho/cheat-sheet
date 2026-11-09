package dev.cheatsheet.exceptions.catalog;

import dev.cheatsheet.exceptions.shared.error.ErrorCode;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registro central de todos os códigos de erro. Valida no startup (fail-fast) que os códigos
 * são únicos e seguem o formato: dois enums com {@code ORDER_NOT_FOUND} derrubam a aplicação
 * na subida, e não em produção, às 3h da manhã.
 */
public final class ErrorCatalog {

    private final Map<String, ErrorCode> byCode;

    public ErrorCatalog(Collection<? extends ErrorCode> codes) {
        Map<String, ErrorCode> map = new LinkedHashMap<>();
        for (ErrorCode code : codes) {
            if (!ErrorCode.FORMAT.matcher(code.code()).matches()) {
                throw new IllegalStateException("Código de erro fora do formato " + ErrorCode.FORMAT + ": " + code.code());
            }
            if (code.title() == null || code.title().isBlank() || code.messageTemplate() == null || code.messageTemplate().isBlank()) {
                throw new IllegalStateException("Código de erro sem título/mensagem: " + code.code());
            }
            ErrorCode previous = map.putIfAbsent(code.code(), code);
            if (previous != null) {
                throw new IllegalStateException("Código de erro duplicado: %s (%s e %s)"
                        .formatted(code.code(), previous.getClass().getSimpleName(), code.getClass().getSimpleName()));
            }
        }
        this.byCode = Collections.unmodifiableMap(map);
    }

    public Collection<ErrorCode> all() {
        return byCode.values();
    }

    public Optional<ErrorCode> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }
}
