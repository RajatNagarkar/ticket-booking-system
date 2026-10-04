package com.assignment.tickets.exception;

import com.assignment.tickets.dto.response.ErrorResponse;
import com.assignment.tickets.observability.RequestContext;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every expected outcome is a 4xx with a stable error code. Anything reaching the
 * fallback handler is a bug and is logged as such.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return error(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest("validation_failed", message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParams(HandlerMethodValidationException e) {
        return badRequest("validation_failed", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return badRequest("malformed_request", "Request body is missing or not valid JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("invalid_parameter", "Invalid value for '" + e.getName() + "'");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // Spring MVC's own client errors (404 no route, 405, 415, missing header, ...) keep their 4xx status.
        if (e instanceof org.springframework.web.ErrorResponse springError
                && springError.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            return error(status, status.name().toLowerCase(), springError.getBody().getDetail());
        }
        log.error("Unhandled exception", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected error");
    }

    private static ResponseEntity<ErrorResponse> badRequest(String code, String message) {
        return error(HttpStatus.BAD_REQUEST, code, message);
    }

    /** The error code also becomes the request's logged outcome. */
    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        RequestContext.put(RequestContext.OUTCOME, code);
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}
