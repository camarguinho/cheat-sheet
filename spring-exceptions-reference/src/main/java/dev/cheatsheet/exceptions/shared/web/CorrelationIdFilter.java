package dev.cheatsheet.exceptions.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Garante um correlationId por requisição: no MDC (aparece em toda linha de log), no header
 * de resposta e no corpo de erro. É ele que liga "o cliente viu um 500" ao log com a stack trace.
 *
 * <p>Também roda no dispatch de ERROR (falhas fora do DispatcherServlet), reaproveitando o
 * mesmo id via atributo de request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".ID";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolve(request);
        request.setAttribute(ATTRIBUTE, id);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String incoming = request.getHeader(HEADER);
        // Nunca confie cegamente em header de entrada: valide antes de pôr no log (log injection).
        return incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
