package com.github.berrywang1996.netty.spring.web.context;

/**
 * Callback interface for components that need access to the shared {@link HandlerSubmitter}.
 *
 * <p>Mapping resolvers or other components that implement this interface will be
 * automatically injected with the {@link HandlerSubmitter} during initialization
 * by {@link WebMappingSupporter}. This allows resolvers to submit work to the
 * shared bounded execution model without direct coupling to the supporter.
 *
 * @author berrywang1996
 * @since V1.0.0
 * @see HandlerSubmitter
 * @see WebMappingSupporter
 */
public interface HandlerSubmitterAware {

    /**
     * Injects the shared handler submitter into this component.
     *
     * @param handlerSubmitter the handler submitter to use for task submission
     */
    void setHandlerSubmitter(HandlerSubmitter handlerSubmitter);

}
