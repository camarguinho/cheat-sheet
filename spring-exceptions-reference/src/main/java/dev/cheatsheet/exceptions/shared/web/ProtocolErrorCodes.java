package dev.cheatsheet.exceptions.shared.web;

import dev.cheatsheet.exceptions.shared.error.CommonErrorCode;
import org.springframework.http.HttpStatusCode;

/**
 * Códigos para erros de <b>protocolo</b> detectados pelo próprio Spring MVC (rota inexistente,
 * método não suportado, JSON ilegível...). Nesses casos o status já vem decidido pelo
 * framework; só anotamos um {@code code} para manter o contrato uniforme.
 */
final class ProtocolErrorCodes {

    private ProtocolErrorCodes() {
    }

    static String forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> CommonErrorCode.MALFORMED_REQUEST.code();
            case 404 -> "ROUTE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status.is5xxServerError() ? CommonErrorCode.INTERNAL_ERROR.code() : "REQUEST_REJECTED";
        };
    }
}
