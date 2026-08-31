package io.github.larkbatis.scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * {@code mybatis-config.xml}: the file that answers "what plugins are you
 * running" — one of the four things a migration report has to list, since a
 * plugin has no LarkBatis equivalent at all.
 */
public final class MyBatisConfigScan {

    private final Path file;
    private final List<Finding> findings = new ArrayList<>();
    private boolean underscoreMappingSeen;
    private int environments;
    /** {@code <package>} means something different under {@code <mappers>}. */
    private boolean inTypeAliases;

    private MyBatisConfigScan(Path file) {
        this.file = file;
    }

    /** @return null when the file is not a MyBatis configuration */
    public static MyBatisConfigScan scan(Path file) throws IOException {
        MyBatisConfigScan scan = new MyBatisConfigScan(file);
        return scan.walk() ? scan : null;
    }

    public List<Finding> findings() {
        return findings;
    }

    private boolean walk() throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        boolean isConfig = false;
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            boolean first = true;
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String name = reader.getLocalName();
                if ("typeAliases".equals(name)) {
                    inTypeAliases = true;
                } else if ("mappers".equals(name) || "typeHandlers".equals(name)) {
                    inTypeAliases = false;
                }
                if (first) {
                    first = false;
                    if (!"configuration".equals(name)) {
                        return false;
                    }
                    isConfig = true;
                }
                inspect(reader, name, reader.getLocation().getLineNumber());
            }
        } catch (XMLStreamException e) {
            return false;
        }
        if (isConfig && !underscoreMappingSeen) {
            // MyBatis defaults it to false and LarkBatis defaults it to on, so
            // a configuration that never mentions the setting still changes
            // behaviour on the way across. Silence here is worse than a noisy
            // line in a report.
            add(0, Rule.UNDERSCORE_MAPPING_OFF,
                    "mapUnderscoreToCamelCase is not set, so MyBatis defaults it to false");
        }
        if (environments > 1) {
            add(0, Rule.MULTIPLE_ENVIRONMENTS, environments + " <environment> entries");
        }
        return isConfig;
    }

    private void inspect(XMLStreamReader reader, String name, int line) {
        switch (name) {
            case "plugin" -> add(line, Rule.PLUGIN,
                    "interceptor=\"" + value(reader, "interceptor") + "\"");
            case "objectFactory", "objectWrapperFactory", "reflectorFactory" ->
                    add(line, Rule.OBJECT_FACTORY, "<" + name + " type=\""
                            + value(reader, "type") + "\">");
            case "typeHandler" -> {
                // With a javaType attribute the whole entry can be written out
                // ready to paste; without one, MyBatis reads it from
                // @MappedTypes or the handler's type argument, and guessing
                // which would put a wrong class name in a report people copy.
                String handler = value(reader, "handler");
                String javaType = value(reader, "javaType");
                // The pair alone, and first: the report clips a detail at 90
                // characters, and the pasteable half is the whole point of
                // printing this one.
                add(line, Rule.TYPE_HANDLER, javaType.isEmpty()
                        ? "handler=" + handler + " — no javaType attribute, so the type it"
                                + " covers has to be read off the handler"
                        : javaType + ":" + handler);
            }
            case "environment" -> environments++;
            case "setting" -> inspectSetting(reader, line);
            case "typeAlias" -> add(line, Rule.TYPE_ALIAS_DECLARED,
                    "<typeAlias alias=\"" + value(reader, "alias") + "\" type=\""
                            + value(reader, "type") + "\">");
            case "package" -> {
                // A package scan aliases every class under it by simple name,
                // so this one line is why a whole mapper set can name its
                // types unqualified. Reported as its own finding because the
                // per-statement count says how much work it caused, and this
                // says where the aliases came from.
                if (inTypeAliases) {
                    add(line, Rule.TYPE_ALIAS_DECLARED,
                            "<package name=\"" + value(reader, "name") + "\"> aliases every"
                                    + " class in that package by simple name");
                }
            }
            default -> { }
        }
    }

    private void inspectSetting(XMLStreamReader reader, int line) {
        String name = reader.getAttributeValue(null, "name");
        String value = reader.getAttributeValue(null, "value");
        if (name == null) {
            return;
        }
        switch (name) {
            case "lazyLoadingEnabled", "aggressiveLazyLoading" -> {
                if ("true".equalsIgnoreCase(value)) {
                    add(line, Rule.LAZY_LOADING, name + "=" + value);
                }
            }
            case "mapUnderscoreToCamelCase" -> {
                underscoreMappingSeen = true;
                if (!"true".equalsIgnoreCase(value)) {
                    add(line, Rule.UNDERSCORE_MAPPING_OFF, name + "=" + value);
                }
            }
            default -> { }
        }
    }

    private static String value(XMLStreamReader reader, String attribute) {
        String found = reader.getAttributeValue(null, attribute);
        return found == null ? "" : found;
    }

    private void add(int line, Rule rule, String detail) {
        findings.add(new Finding(file, line, rule, null, detail));
    }
}
