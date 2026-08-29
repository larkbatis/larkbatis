package io.github.lightbatis.processor.frontend;

import javax.lang.model.element.Element;

/**
 * A build-time rejection: the mapper shape is outside what LightBatis
 * supports, and the fix belongs in the mapper (build plan §08). Carries the
 * element the error should be reported on, so the message lands on the right
 * line in the IDE.
 */
public final class LightBatisProcessingException extends RuntimeException {

    private final transient Element element;

    public LightBatisProcessingException(Element element, String message) {
        super(message);
        this.element = element;
    }

    public Element element() {
        return element;
    }
}
