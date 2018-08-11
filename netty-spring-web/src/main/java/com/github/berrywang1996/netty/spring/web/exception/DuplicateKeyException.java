package com.github.berrywang1996.netty.spring.web.exception;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
public class DuplicateKeyException extends RuntimeException {

    public DuplicateKeyException(String message) {
        super(message);
    }

}
