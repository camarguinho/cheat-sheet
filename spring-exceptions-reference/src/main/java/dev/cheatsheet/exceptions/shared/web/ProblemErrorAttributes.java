package dev.cheatsheet.exceptions.shared.web;

import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * Erros que acontecem <b>fora</b> do DispatcherServlet (num Filter, por exemplo) não passam pelo
 * {@code @RestControllerAdvice}; o container encaminha para {@code /error} (BasicErrorController).
 * Este bean deixa essa resposta o mais próxima possível do contrato: com code e correlationId,
 * e sem vazar a mensagem da exceção.
 */
@Component
class ProblemErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        Object status = attributes.get("status");
        int value = status instanceof Integer i ? i : 500;
        attributes.put("code", ProtocolErrorCodes.forStatus(org.springframework.http.HttpStatusCode.valueOf(value)));
        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            attributes.put("correlationId", correlationId);
        }
        return attributes;
    }
}
