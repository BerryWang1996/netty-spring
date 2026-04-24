package com.github.berrywang1996.netty.spring.web.context;

/**
 * Callback for components that need the shared handler submitter.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public interface HandlerSubmitterAware {

    void setHandlerSubmitter(HandlerSubmitter handlerSubmitter);

}
