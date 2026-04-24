package com.github.berrywang1996.netty.spring.web.context;

/**
 * Submit handler work onto the shared bounded execution model.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public interface HandlerSubmitter {

    void submitHandle(Runnable runnable);

}
