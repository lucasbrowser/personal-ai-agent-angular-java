package com.jarvis.ai.conversation.controller;

import com.jarvis.ai.conversation.service.ConversationNotFoundException;
import com.jarvis.ai.conversation.service.InvalidMessageException;
import java.util.Map;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = ConversationController.class)
public class ConversationExceptionHandler {
    @ExceptionHandler(ConversationNotFoundException.class)
    ResponseEntity<?> notFound() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "A conversa solicitada não foi encontrada.");
    }

    @ExceptionHandler({
            InvalidMessageException.class,
            HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<?> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dados da requisição inválidos.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() {
        return error(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "A operação conflita com o estado atual dos dados.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internalError() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Não foi possível concluir a operação.");
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
