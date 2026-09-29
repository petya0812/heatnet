package ru.lct.heatnet.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.Map;

/**
 * Errors raised before a handler method is chosen (wrong Content-Type, wrong method): the same {"error"} body as
 * the errors of {@link ApiController}, instead of the default body of Spring.
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> mediaType(HttpMediaTypeNotSupportedException e) {
        boolean upload = e.getSupportedMediaTypes().contains(MediaType.MULTIPART_FORM_DATA);
        String got = e.getContentType() == null ? "Content-Type is missing" : "Content-Type " + e.getContentType() + " is not supported here";
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, got + "; expected " + e.getSupportedMediaTypes()
                + (upload ? " — upload the file as a form field: curl -F file=@data.geojson" : ""));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> method(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method " + e.getMethod() + " is not supported here; supported: "
                + e.getSupportedHttpMethods());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Collections.singletonMap("error", message));
    }
}
