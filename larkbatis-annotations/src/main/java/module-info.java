/**
 * Mapper annotations. No logic, no dependencies — consumers need this module
 * to compile and never at run time, so they declare it {@code requires
 * static} (every annotation here is {@code RetentionPolicy.CLASS}).
 */
module io.github.larkbatis.annotations {
    exports io.github.larkbatis.annotations;
}
