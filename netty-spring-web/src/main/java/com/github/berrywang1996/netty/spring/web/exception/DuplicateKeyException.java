package com.github.berrywang1996.netty.spring.web.exception;

/**
 * @Author: 王伯瑞
 * @Date: 2018/8/11 12:28
 */
public class DuplicateKeyException extends RuntimeException {

    public DuplicateKeyException(String message) {
        super(message);
    }
}
