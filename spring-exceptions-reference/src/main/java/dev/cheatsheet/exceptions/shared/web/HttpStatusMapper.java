package dev.cheatsheet.exceptions.shared.web;

import dev.cheatsheet.exceptions.shared.error.ErrorCategory;
import org.springframework.http.HttpStatus;

/**
 * Único ponto que traduz categoria de erro em HTTP status. O {@code switch} é exaustivo e
 * sem {@code default}: criar uma categoria nova sem mapear aqui não compila.
 */
public final class HttpStatusMapper {

    private HttpStatusMapper() {
    }

    public static HttpStatus toStatus(ErrorCategory category) {
        return switch (category) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;                    // 400
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;              // 401
            case FORBIDDEN -> HttpStatus.FORBIDDEN;                       // 403
            case NOT_FOUND -> HttpStatus.NOT_FOUND;                       // 404
            case CONFLICT -> HttpStatus.CONFLICT;                         // 409
            case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_CONTENT;       // 422
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;            // 429
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;            // 500
            case DEPENDENCY_FAILURE -> HttpStatus.BAD_GATEWAY;            // 502
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;           // 503
            case DEPENDENCY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;        // 504
        };
    }
}
