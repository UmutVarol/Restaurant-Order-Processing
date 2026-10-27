package com.umutvarol.restaurant.common.error;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into RFC 9457 {@code application/problem+json} responses. Every problem carries
 * a stable {@code code} property so that clients can localise messages without parsing English text.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated", ex);
    }

    @ExceptionHandler(InvalidStateException.class)
    ProblemDetail handleInvalidState(InvalidStateException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid state transition", ex);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(TooManyRequestsException.retryAfterSeconds(ex.getRetryAfter())))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", ex));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex) {
        return payloadTooLarge(ex);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was modified by someone else. Refresh and try again.");
        problem.setTitle("Concurrent modification");
        return withCode(problem, "CONCURRENT_MODIFICATION");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request parameters are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return withCode(problem, "VALIDATION_FAILED");
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        problem.setTitle("Authentication failed");
        return withCode(problem, "BAD_CREDENTIALS");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Authentication is required to access this resource.");
        problem.setTitle("Unauthorized");
        return withCode(problem, "UNAUTHORIZED");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Your role is not allowed to perform this action.");
        problem.setTitle("Forbidden");
        return withCode(problem, "FORBIDDEN");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.");
        problem.setTitle("Internal server error");
        return withCode(problem, "INTERNAL_ERROR");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        List<String> globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> String.valueOf(error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                globalErrors.isEmpty() ? "One or more fields are invalid." : String.join(" ", globalErrors));
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        withCode(problem, "VALIDATION_FAILED");
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request parameters are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        withCode(problem, "VALIDATION_FAILED");
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        // A body cut off by the size limit surfaces here, wrapped by the JSON reader.
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException tooLarge) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).headers(headers).body(payloadTooLarge(tooLarge));
            }
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body is missing or is not valid JSON of the expected shape.");
        problem.setTitle("Malformed request");
        withCode(problem, "MALFORMED_REQUEST");
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail handleInvalidSort(PropertyReferenceException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Cannot sort by '%s'.".formatted(ex.getPropertyName()));
        problem.setTitle("Invalid sort property");
        return withCode(problem, "INVALID_SORT");
    }

    private static ProblemDetail payloadTooLarge(PayloadTooLargeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "The request body is larger than the %d bytes this API accepts.".formatted(ex.getLimitBytes()));
        problem.setTitle("Request too large");
        return withCode(problem, "PAYLOAD_TOO_LARGE");
    }

    private static ProblemDetail problem(HttpStatus status, String title, DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(title);
        if (!ex.getParameters().isEmpty()) {
            problem.setProperty("parameters", ex.getParameters());
        }
        return withCode(problem, ex.getCode());
    }

    private static ProblemDetail withCode(ProblemDetail problem, String code) {
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
