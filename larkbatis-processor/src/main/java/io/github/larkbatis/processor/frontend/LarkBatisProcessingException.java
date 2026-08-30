package io.github.larkbatis.processor.frontend;

import javax.lang.model.element.Element;

/**
 * A build-time rejection: the mapper shape is outside what LarkBatis
 * supports, and the fix belongs in the mapper. Carries the
 * element the error should be reported on, so the message lands on the right
 * line in the IDE.
 */
public final class LarkBatisProcessingException extends RuntimeException {

    private final transient Element element;

    public LarkBatisProcessingException(Element element, String message) {
        super(message);
        this.element = element;
    }

    public Element element() {
        return element;
    }
}
