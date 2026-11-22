package com.statagora;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
  static class Failure extends RuntimeException {
    final HttpStatus status;

    Failure(HttpStatus status, String message) {
      super(message);
      this.status = status;
    }
  }

  static Failure bad(String message) {
    return new Failure(HttpStatus.BAD_REQUEST, message);
  }

  static Failure missing() {
    return new Failure(HttpStatus.NOT_FOUND, "Resource not found");
  }

  @ExceptionHandler(Failure.class)
  ResponseEntity<?> failure(Failure e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "message", "Invalid request. Check field lengths, language, year and pagination."));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<?> duplicate() {
    return ResponseEntity.status(409).body(Map.of("message", "Already exists"));
  }
}
