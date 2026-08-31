package io.github.larkbatis.scanner;

/**
 * The catalogue of things a MyBatis codebase can contain that LarkBatis
 * treats differently.
 *
 * <p>Every entry carries a {@code topic} — the heading in {@code MIGRATION.md}
 * that explains it — so a report line traces back to a written decision rather
 * than to the tool's opinion. The topic, and not a pointer into this project's
 * own design notes, because the person reading a scan report is holding
 * someone else's codebase: the only document they can be assumed to have is
 * the one that ships next to the tool.
 */
public enum Rule {

    DOLLAR_SPLICE(Severity.EDIT, "raw-sql", "${} splice",
            "Declare the parameter as SqlFragment, or as a closed-value type"
                    + " (int/long/boolean/enum), or as @OrderBy(allowed={...}) String."
                    + " At the call site a String becomes SqlFragment.identifier(x)."),

    DOLLAR_IN_SELECT_LIST(Severity.REVIEW, "row-readers", "${} inside a select list",
            "The generator cannot parse the select list, so this one statement"
                    + " falls back to a name-based row reader resolved from"
                    + " ResultSetMetaData. Correct, measurably slower — decide"
                    + " whether the column list can be made static."),

    EXPRESSION_OUTSIDE_GRAMMAR(Severity.EDIT, "expressions", "test= outside the expression grammar",
            "The grammar is null checks, comparisons on typed property paths,"
                    + " and/or/not, size()/length()/isEmpty(), boolean-returning"
                    + " methods, and bare booleans. Rewrite the test, or move the"
                    + " decision into Java and pass the result in."),

    EXPRESSION_VALUE_CALL(Severity.EDIT, "expressions", "test= calls a value method",
            "A condition may call size(), length(), isEmpty() or a"
                    + " boolean-returning method. A call that answers with a"
                    + " value — trim(), toString(), get() — is refused, so"
                    + " name != null and name.trim() != '' does not compile."
                    + " Write name != null and !name.isEmpty(), or trim on the"
                    + " way in and pass the result."),

    EXPRESSION_UNTYPED_CALL(Severity.REVIEW, "expressions", "test= calls a method of its own",
            "Accepted if it returns boolean (or int, for size/length), refused"
                    + " otherwise — and which it is depends on a class this scan"
                    + " does not compile. Check the return type; nothing to do"
                    + " if it is already boolean."),

    EXPRESSION_BARE_PATH(Severity.EDIT, "expressions", "OGNL truthiness",
            "MyBatis treats a non-null, non-zero, non-empty value as true."
                    + " LarkBatis refuses to guess which of those was meant:"
                    + " write count != 0, user != null, or list.isEmpty() explicitly."),

    MAP_PARAMETER(Severity.BLOCKER, "parameters", "Map or Object parameter",
            "There is no type to resolve #{} against. Introduce a parameter"
                    + " object, or split the map into @Param arguments."),

    MAP_RESULT(Severity.BLOCKER, "result-classes", "Map or Object result type",
            "resultType=\"map\" builds a HashMap per row out of column labels,"
                    + " which is the reflection layer being removed. Declare a"
                    + " result class with setters, or read the rows through the"
                    + " escape hatch."),

    UNSUPPORTED_PROPERTY_TYPE(Severity.BLOCKER, "result-classes",
            "result property outside the type whitelist",
            "The generator moves a closed set of types across JDBC and refuses"
                    + " the rest rather than emit a silently unmapped field. The"
                    + " date and number types a legacy DTO is full of are all in"
                    + " that set; what is left here is mostly types that were"
                    + " never a column — a Map, a Set, an Optional. Change the"
                    + " property, or keep the type and name a handler for it with"
                    + " @Handler."),

    DEEP_PROPERTY_PATH(Severity.BLOCKER, "parameters", "#{} more than one property deep",
            "One property hop is resolved — #{user.name}, or #{u.name} with"
                    + " @Param(\"u\"). #{order.customer.name} is not. Pass the"
                    + " inner value as its own parameter, or flatten the"
                    + " parameter object."),

    OVERLOADED_MAPPER_METHOD(Severity.BLOCKER, "mapper-interfaces", "overloaded mapper method",
            "The generated SQL constants are named after the method, so two"
                    + " methods sharing a name collide. Rename one side."),

    MAPPER_INHERITANCE(Severity.BLOCKER, "mapper-interfaces",
            "mapper interface extends another interface",
            "Statements are read from the interface itself, so a method"
                    + " inherited from a base interface gets no implementation."
                    + " Declare every statement on the mapper interface itself;"
                    + " a shared base interface has no equivalent."),

    PROVIDER_ANNOTATION(Severity.BLOCKER, "dropped-features", "@SelectProvider family",
            "SQL built by a Java method at runtime is exactly what the"
                    + " generator cannot see. Move the SQL into the mapper, or"
                    + " use the escape hatch with SqlFragment."),

    PLUGIN(Severity.BLOCKER, "dropped-features", "plugin / interceptor",
            "An Interceptor wraps four objects — Executor, StatementHandler,"
                    + " ParameterHandler, ResultSetHandler — and those are what a"
                    + " generated method body replaces, so there is nothing left"
                    + " to wrap. Paging becomes LIMIT/OFFSET parameters, auditing"
                    + " moves to the service or a <sql> fragment, soft delete"
                    + " becomes an explicit predicate, column encryption becomes a"
                    + " type handler, and timing becomes a decorator around the"
                    + " mapper bean. The migration guide lists one replacement per"
                    + " plugin kind."),

    LAZY_LOADING(Severity.BLOCKER, "dropped-features", "lazy loading",
            "Lazy loading needs a proxy per result object. Fetch eagerly with a"
                    + " join, or split into two statements."),

    NESTED_SELECT(Severity.BLOCKER, "result-maps", "nested select in a result mapping",
            "association/collection with select= issues N+1 queries through the"
                    + " runtime. Express the mapping as a join instead."),

    RESULT_MAP_DEPTH(Severity.BLOCKER, "result-maps", "result map nested more than one level",
            "Nesting stops at one level over a join. Deeper graphs are assembled"
                    + " in Java from two statements."),

    RESULT_MAP_EXTENDS(Severity.BLOCKER, "result-maps", "result map inheritance",
            "extends= is not resolved. Write the mappings out."),

    DISCRIMINATOR(Severity.BLOCKER, "dropped-features", "<discriminator>",
            "The result class is chosen at runtime from a column value."
                    + " Use separate statements with separate result types."),

    CONSTRUCTOR_RESULT(Severity.BLOCKER, "result-classes", "<constructor> result",
            "Result classes are built with a no-arg constructor and setters."),

    BIND(Severity.BLOCKER, "dropped-features", "<bind>",
            "<bind> introduces an OGNL variable. Compute the value in Java and"
                    + " pass it as a parameter."),

    PARAMETER_MAP(Severity.BLOCKER, "dropped-features", "<parameterMap>",
            "Deprecated in MyBatis too. Use #{} with typed parameters."),

    SECOND_LEVEL_CACHE(Severity.BLOCKER, "dropped-features", "second-level cache",
            "<cache>/<cache-ref> has no equivalent. Cache above the mapper, in"
                    + " the service, where invalidation is visible."),

    ROW_BOUNDS(Severity.BLOCKER, "dropped-features", "RowBounds",
            "In-memory paging over a full ResultSet. Page in SQL with LIMIT and"
                    + " OFFSET as real parameters."),

    SELECT_KEY(Severity.REVIEW, "generated-keys", "<selectKey>",
            "Generated keys go through useGeneratedKeys with explicit"
                    + " keyProperty and keyColumn. A <selectKey> that reads a"
                    + " sequence becomes a statement of its own."),

    TYPE_HANDLER(Severity.REVIEW, "type-handlers", "custom TypeHandler",
            "The attribute is read, so the mapper itself needs no edit. What"
                    + " changes is the handler class: implement"
                    + " io.github.larkbatis.runtime.LarkBatisTypeHandler instead"
                    + " of org.apache.ibatis.type.TypeHandler. A handler"
                    + " registered only in <typeHandlers> carries across as an"
                    + " -Alarkbatis.typeHandlers entry, one javaType:handler pair"
                    + " per registration, resolved during javac. Nothing is"
                    + " scanned, so a handler MyBatis found through @MappedTypes"
                    + " or a package scan has to be written out."),

    INLINE_TYPE_HANDLER(Severity.REVIEW, "type-handlers", "typeHandler= inside #{}",
            "Read like the attribute form, so the SQL needs no edit — same"
                    + " handler-class rewrite. Counted separately because it sits"
                    + " in SQL text rather than in an element, which is what makes"
                    + " it easy to miss when costing the handler work."),

    OBJECT_FACTORY(Severity.BLOCKER, "dropped-features", "objectFactory / objectWrapperFactory",
            "These are hooks into the reflection layer that no longer exists."),

    DYNAMIC_INCLUDE(Severity.BLOCKER, "raw-sql", "<include> with a computed refid",
            "refid is resolved and inlined at build time, so it has to be a"
                    + " literal."),

    SCRIPT_ANNOTATION(Severity.REVIEW, "expressions", "<script> in an annotation",
            "Dynamic SQL inside a Java annotation is read, but the same grammar"
                    + " rules apply as in XML — check the tests it contains."),

    SQL_SESSION_CALL(Severity.REVIEW, "escape-hatch", "direct SqlSession use",
            "selectList/selectOne/insert by statement id has no compile-time"
                    + " type. Call the mapper, or use the escape hatch:"
                    + " session.query(SqlFragment, binder, GeneratedRow.READER)."),

    UNDERSCORE_MAPPING_OFF(Severity.REVIEW, "row-readers", "mapUnderscoreToCamelCase is off",
            "LarkBatis applies underscore-to-camelCase at build time, and defaults"
                    + " it to on. Carry this setting across with"
                    + " -Alarkbatis.mapUnderscoreToCamelCase=false, or leave it on and"
                    + " give the affected columns an @Column. Leaving it on without"
                    + " either is a behaviour change: columns MyBatis left unmapped"
                    + " start being read."),

    MULTIPLE_ENVIRONMENTS(Severity.REVIEW, "spring", "more than one environment",
            "Several DataSources need @LarkBatisDataSource, which is deferred."
                    + " One DataSource per build for now."),

    STATEMENT_TYPE(Severity.BLOCKER, "escape-hatch", "statementType other than PREPARED",
            "CALLABLE and STATEMENT are not generated. A stored-procedure call"
                    + " goes through the escape hatch."),

    TYPE_ALIAS_DECLARED(Severity.EDIT, "result-classes", "typeAlias declaration",
            "Aliases are a runtime name-resolution table, and there is no"
                    + " runtime Configuration to hold one. Every resultType,"
                    + " parameterType, javaType and ofType that uses an alias"
                    + " becomes the fully-qualified class name. A <package>"
                    + " scan aliases every class in it, so the count below is"
                    + " the statements to edit, not the aliases declared."),

    UNQUALIFIED_TYPE_NAME(Severity.EDIT, "result-classes", "type named by alias",
            "Not a fully-qualified class name, so this is a typeAlias, a"
                    + " built-in alias, or a package-scanned class. Replace it"
                    + " with the FQN. This scan deliberately does not guess"
                    + " which class it is — a wrong file name in a migration"
                    + " report is worse than a name it declines to resolve."),

    PARSE_REJECTED(Severity.BLOCKER, "reading-this-report", "the frontend rejected the file",
            "The mapper XML does not get as far as being lowered. The diagnostic"
                    + " is the frontend's own."),

    FOREACH(Severity.INFO, "foreach", "<foreach>",
            "Supported over statically-typed collections. Counted here because"
                    + " it is what makes a statement's SQL variant count grow."),

    DYNAMIC_STATEMENT(Severity.INFO, "expressions", "dynamic statement",
            "Compiles to boolean locals and a StringBuilder.");

    private final Severity severity;
    private final String topic;
    private final String title;
    private final String guidance;

    Rule(Severity severity, String topic, String title, String guidance) {
        this.severity = severity;
        this.topic = topic;
        this.title = title;
        this.guidance = guidance;
    }

    public Severity severity() {
        return severity;
    }

    /** The MIGRATION.md heading that explains this rule. */
    public String topic() {
        return topic;
    }

    public String title() {
        return title;
    }

    public String guidance() {
        return guidance;
    }
}
