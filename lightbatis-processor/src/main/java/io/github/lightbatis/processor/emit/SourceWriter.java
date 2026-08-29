package io.github.lightbatis.processor.emit;

import javax.lang.model.element.Element;

/**
 * Where generated sources go. The only JavaPoet-free seam between the
 * emitters and the environment (build plan §01): emitters render to text and
 * hand it here, so replacing JavaPoet later means rewriting emitters only,
 * and tests can capture output without a Filer.
 */
public interface SourceWriter {

    void write(String fqcn, String content, Element... originatingElements);
}
