package com.sitemanager.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Application-wide exception handling for conditions that are not actionable bugs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Raised when the response can no longer be written because the client closed the
     * connection before the response was fully flushed (e.g. browser navigation away,
     * request timeout, dropped network). There is nothing the server can do — the
     * channel is gone — so this is handled here to keep it out of the WARN-level noise
     * emitted by Spring's DefaultHandlerExceptionResolver. Returning void marks the
     * request as resolved without attempting a (doomed) write to the closed channel.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected before the response could be flushed: {}", ex.getMessage());
    }
}
