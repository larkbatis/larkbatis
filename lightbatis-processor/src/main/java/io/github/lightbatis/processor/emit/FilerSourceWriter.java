package io.github.lightbatis.processor.emit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;

/** Writes generated sources through the annotation-processing {@link Filer}. */
public final class FilerSourceWriter implements SourceWriter {

    private final Filer filer;

    public FilerSourceWriter(Filer filer) {
        this.filer = filer;
    }

    @Override
    public void write(String fqcn, String content, Element... originatingElements) {
        try (Writer writer = filer.createSourceFile(fqcn, originatingElements).openWriter()) {
            writer.write(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write generated source " + fqcn, e);
        }
    }
}
