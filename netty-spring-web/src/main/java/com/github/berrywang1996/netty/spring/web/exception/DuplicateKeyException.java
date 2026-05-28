package com.github.berrywang1996.netty.spring.web.exception;

/**
 * Exception thrown when a duplicate mapping key is detected during URL mapping registration.
 *
 * <p>This typically occurs at application startup when two or more controllers attempt to
 * register the same URL pattern, which would lead to ambiguous request routing.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public class DuplicateKeyException extends RuntimeException {

    /**
     * Constructs a new {@code DuplicateKeyException} with the specified detail message.
     *
     * @param message a description of the duplicate key conflict
     */
    public DuplicateKeyException(String message) {
        super(message);
    }

}
