package dev.cheatsheet.exceptions.shared.web;

import dev.cheatsheet.exceptions.shared.error.ApplicationException;
import dev.cheatsheet.exceptions.shared.error.CommonErrorCode;
import dev.cheatsheet.exceptions.shared.error.ErrorCode;
import dev.cheatsheet.exceptions.shared.error.IntegrationException;
import jakarta.validation.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tradutor central: exceção → resposta HTTP no formato Problem Details (RFC 9457).
 *
 * <p>Três famílias de erro, três tratamentos:
 * <ol>
 *   <li><b>Erros da aplicação</b> ({@link ApplicationException}): status derivado da categoria do
 *       {@link ErrorCode}; mensagem e detalhes vão para o cliente.</li>
 *   <li><b>Erros de protocolo/framework</b> (rota inexistente, JSON inválido, validação): tratados
 *       pela superclasse {@link ResponseEntityExceptionHandler}; aqui só enriquecemos o corpo.</li>
 *   <li><b>Qualquer outra coisa</b> = bug: 500 genérico, sem vazar detalhe, com stack trace no log.</li>
 * </ol>
 *
 * <p>Política de log: cada exceção é logada <b>uma única vez</b>, aqui. 4xx sem stack trace
 * (não é problema nosso), 5xx com stack trace (é problema nosso).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorProperties properties;

    public GlobalExceptionHandler(ErrorProperties properties) {
        this.properties = properties;
    }

    // ---------------------------------------------------------------- 1. Erros da aplicação

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> handleApplicationException(ApplicationException ex) {
        ErrorCode code = ex.getErrorCode();
        HttpStatus status = HttpStatusMapper.toStatus(code.category());
        ProblemDetail problem = problemFor(status, code, ex.getMessage());
        ex.getDetails().forEach(problem::setProperty);

        HttpHeaders headers = new HttpHeaders();
        if (ex instanceof IntegrationException integration) {
            problem.setProperty("retryable", integration.isRetryable());
            integration.getRetryAfter().ifPresent(delay ->
                    headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(delay.toSeconds())));
        }

        logException(ex, status, code.code());
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    /** Violação de {@code @Validated} em beans de serviço (não-controller). */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        ProblemDetail problem = validationProblem(violations);
        logException(ex, HttpStatus.BAD_REQUEST, CommonErrorCode.VALIDATION_FAILED.code());
        return ResponseEntity.badRequest().body(problem);
    }

    // ---------------------------------------------------------------- 3. Rede de segurança

    /**
     * Tudo que não foi previsto. A mensagem original <b>nunca</b> vai para o cliente: pode conter
     * SQL, nome de tabela, caminho de arquivo, dado pessoal. O correlationId liga a resposta ao log.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = problemFor(status, CommonErrorCode.INTERNAL_ERROR,
                CommonErrorCode.INTERNAL_ERROR.messageTemplate());
        logException(ex, status, CommonErrorCode.INTERNAL_ERROR.code());
        return ResponseEntity.status(status).body(problem);
    }

    // ---------------------------------------------------------------- 2. Erros do framework

    /** {@code @Valid @RequestBody} inválido. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> violations.add(toViolation(e)));
        ex.getBindingResult().getGlobalErrors().forEach(e -> violations.add(new FieldViolation(e.getObjectName(), e.getDefaultMessage())));
        violations.sort(Comparator.comparing(FieldViolation::field));
        return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
    }

    /** Constraints direto em parâmetros do controller ({@code @RequestParam @Size ...}). Spring 6.1+. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors errors) {
                errors.getFieldErrors().forEach(e -> violations.add(toViolation(e)));
            } else {
                String name = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    violations.add(new FieldViolation(name, error.getDefaultMessage()));
                }
            }
        });
        violations.sort(Comparator.comparing(FieldViolation::field));
        return handleExceptionInternal(ex, validationProblem(violations), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Funil por onde passam todas as respostas da superclasse (404 de rota, 405, 415, JSON
     * malformado, type mismatch...). Aqui garantimos que elas sigam o mesmo contrato.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            String code = problem.getProperties() != null && problem.getProperties().get("code") instanceof String c
                    ? c : ProtocolErrorCodes.forStatus(statusCode);
            enrich(problem, code);
            logException(ex, statusCode, code);
        }
        return response;
    }

    // ---------------------------------------------------------------- helpers

    private ProblemDetail problemFor(HttpStatusCode status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(properties.typeFor(code.code()));
        problem.setTitle(code.title());
        enrich(problem, code.code());
        return problem;
    }

    private ProblemDetail validationProblem(List<FieldViolation> violations) {
        CommonErrorCode code = CommonErrorCode.VALIDATION_FAILED;
        ProblemDetail problem = problemFor(HttpStatus.BAD_REQUEST, code, code.messageTemplate());
        problem.setProperty("errors", violations);
        return problem;
    }

    private static void enrich(ProblemDetail problem, String code) {
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private static void logException(Exception ex, HttpStatusCode status, String code) {
        if (status.is5xxServerError()) {
            if (ex instanceof IntegrationException) {
                // Falha de dependência: importante, mas o alerta deve vir da métrica, não do log.
                log.warn("Falha de integração [{} {}]: {}", status.value(), code, ex.getMessage(), ex);
            } else {
                log.error("Erro inesperado [{} {}]", status.value(), code, ex);
            }
        } else if (ex instanceof ApplicationException) {
            log.info("Requisição rejeitada [{} {}]: {}", status.value(), code, ex.getMessage());
        } else {
            log.debug("Requisição inválida [{} {}]: {}", status.value(), code, ex.getMessage());
        }
    }
}
