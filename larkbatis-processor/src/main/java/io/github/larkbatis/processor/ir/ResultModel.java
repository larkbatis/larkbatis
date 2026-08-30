package io.github.larkbatis.processor.ir;

import java.util.List;

/**
 * A bean result class and its generated row reader (one reader per result
 * class, landing in the result class's package).
 *
 * @param fqn        fully-qualified name of the result class
 * @param packageName package the reader is generated into
 * @param simpleName simple name of the result class
 * @param properties writable properties in declaration order (canonical column order)
 */
public record ResultModel(String fqn, String packageName, String simpleName,
                          List<PropertyModel> properties) {

    public String readerSimpleName() {
        return simpleName + "Row";
    }

    public String readerFqn() {
        return packageName.isEmpty() ? readerSimpleName() : packageName + "." + readerSimpleName();
    }
}
