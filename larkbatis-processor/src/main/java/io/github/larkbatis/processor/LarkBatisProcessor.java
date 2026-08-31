package io.github.larkbatis.processor;

import io.github.larkbatis.processor.emit.FilerSourceWriter;
import io.github.larkbatis.processor.emit.MapperImplEmitter;
import io.github.larkbatis.processor.emit.RegistryEmitter;
import io.github.larkbatis.processor.emit.RowReaderEmitter;
import io.github.larkbatis.processor.emit.SourceWriter;
import io.github.larkbatis.processor.emit.SpringConfigurationEmitter;
import io.github.larkbatis.processor.frontend.AnnotationFrontend;
import io.github.larkbatis.processor.frontend.LarkBatisProcessingException;
import io.github.larkbatis.processor.frontend.TypeHandlerDefaults;
import io.github.larkbatis.processor.frontend.xml.MapperXmlParser;
import io.github.larkbatis.processor.ir.ColumnNaming;
import io.github.larkbatis.processor.ir.MapperModel;
import io.github.larkbatis.processor.ir.ResultModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * The LarkBatis annotation processor. Declared
 * {@code aggregating} for Gradle incremental processing: a result class can be
 * the resultType of several mappers and the registry spans all of them, so
 * outputs have no single originating element.
 *
 * <p>The XML mapper path is driven by {@code -Alarkbatis.mapperDir}, which
 * the Gradle and Maven plugins pass automatically. It holds one directory or
 * several, separated by the platform path separator or a comma; one option
 * rather than several, because a repeated {@code -A} of the same name is the
 * last one javac reads, not the union.
 */
@SupportedOptions({
        LarkBatisProcessor.OPTION_MAPPER_DIR,
        LarkBatisProcessor.OPTION_MAP_UNDERSCORE_TO_CAMEL_CASE,
        LarkBatisProcessor.OPTION_TYPE_HANDLERS,
        LarkBatisProcessor.OPTION_REGISTRY_PACKAGE,
        LarkBatisProcessor.OPTION_SPRING_CONFIG,
        LarkBatisProcessor.OPTION_SPRING_CONFIG_PACKAGE})
public class LarkBatisProcessor extends AbstractProcessor {

    /** Directories of mapper XML: path-separator or comma separated. */
    public static final String OPTION_MAPPER_DIR = "larkbatis.mapperDir";
    /**
     * {@code false} makes underscores significant when a ResultSet label is
     * matched to a property, the way MyBatis behaves with its setting of the
     * same name off. Named after the MyBatis setting so a migrating build can
     * carry its answer across unchanged.
     */
    public static final String OPTION_MAP_UNDERSCORE_TO_CAMEL_CASE =
            "larkbatis.mapUnderscoreToCamelCase";
    /**
     * A default handler per Java type,
     * {@code com.example.Money:com.example.MoneyHandler,...} — the build-time
     * answer to a {@code mybatis-config.xml} {@code <typeHandlers>} block.
     */
    public static final String OPTION_TYPE_HANDLERS = "larkbatis.typeHandlers";
    public static final String OPTION_REGISTRY_PACKAGE = "larkbatis.registryPackage";
    /** {@code false} suppresses the generated Spring {@code @Configuration}. */
    public static final String OPTION_SPRING_CONFIG = "larkbatis.springConfig";
    /** Package the generated Spring {@code @Configuration} lands in. */
    public static final String OPTION_SPRING_CONFIG_PACKAGE = "larkbatis.springConfigPackage";

    /** Presence of this type is what "spring-context is on the build classpath" means. */
    private static final String SPRING_CONFIGURATION_ANNOTATION =
            "org.springframework.context.annotation.Configuration";

    private static final String MAPPER_ANNOTATION = "io.github.larkbatis.annotations.Mapper";

    /** Asks for a row reader for a class no statement returns — the escape hatch reuses it. */
    private static final String ROW_ANNOTATION = "io.github.larkbatis.annotations.LarkBatisRow";

    private static final Set<String> STATEMENT_ANNOTATIONS = Set.of(
            "io.github.larkbatis.annotations.Select",
            "io.github.larkbatis.annotations.Insert",
            "io.github.larkbatis.annotations.Update",
            "io.github.larkbatis.annotations.Delete");

    /** One reader per result class, across every mapper and round. */
    private final Map<String, ResultModel> resultModels = new HashMap<>();
    /** Reader FQN to the result class it was emitted for: two result classes cannot share one. */
    private final Map<String, String> emittedReaders = new HashMap<>();
    /** Result classes whose reader-name clash has been reported — rounds repeat, errors should not. */
    private final Set<String> reportedReaderClashes = new HashSet<>();
    private boolean registryEmitted;
    private boolean springConfigEmitted;

    private AnnotationFrontend frontend;
    private ColumnNaming columnNaming = ColumnNaming.DEFAULT;
    private SourceWriter sourceWriter;

    /** Mapper XML by namespace, parsed once from -Alarkbatis.mapperDir. */
    private Map<String, MapperXmlParser.XmlMapper> xmlByNamespace;
    private final Set<String> consumedNamespaces = new HashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        columnNaming = columnNaming(env);
        frontend = new AnnotationFrontend(env, resultModels, columnNaming,
                TypeHandlerDefaults.parse(env.getOptions().get(OPTION_TYPE_HANDLERS)));
        sourceWriter = new FilerSourceWriter(env.getFiler());
    }

    /**
     * How ResultSet labels are matched to properties, from
     * {@code -Alarkbatis.mapUnderscoreToCamelCase}.
     *
     * <p>An unrecognized value is an error rather than a silent fallback to
     * the default. This option decides which columns reach which setters, so a
     * mistyped {@code no} taken as {@code true} would not fail the build — it
     * would map columns the author asked to leave alone, and the first sign of
     * it would be data.
     */
    private ColumnNaming columnNaming(ProcessingEnvironment env) {
        String option = env.getOptions().get(OPTION_MAP_UNDERSCORE_TO_CAMEL_CASE);
        if (option == null) {
            return ColumnNaming.DEFAULT;
        }
        String value = option.trim();
        if (value.equalsIgnoreCase("true")) {
            return ColumnNaming.UNDERSCORE_TO_CAMEL_CASE;
        }
        if (value.equalsIgnoreCase("false")) {
            return ColumnNaming.EXACT;
        }
        env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "-A" + OPTION_MAP_UNDERSCORE_TO_CAMEL_CASE + "=" + option
                        + " is not a boolean — write true or false");
        return ColumnNaming.DEFAULT;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new HashSet<>(STATEMENT_ANNOTATIONS);
        types.add(MAPPER_ANNOTATION);
        // claimed so that a compilation unit holding only @LarkBatisRow
        // classes still runs this processor
        types.add(ROW_ANNOTATION);
        return types;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (xmlByNamespace == null) {
            xmlByNamespace = parseMapperXml();
            // Once for the compilation, and before the first entry is used:
            // resolving the classes needs the type model, which init() is too
            // early for.
            for (String problem : frontend.typeHandlerDefaultProblems()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, problem);
            }
        }
        // deterministic order: mappers sorted by FQN
        TreeMap<String, TypeElement> mappers = new TreeMap<>();
        TreeMap<String, TypeElement> rowClasses = new TreeMap<>();
        for (TypeElement annotation : annotations) {
            if (annotation.getQualifiedName().contentEquals(ROW_ANNOTATION)) {
                for (Element annotated : roundEnv.getElementsAnnotatedWith(annotation)) {
                    if (annotated instanceof TypeElement type) {
                        rowClasses.put(type.getQualifiedName().toString(), type);
                    }
                }
                continue;
            }
            boolean onInterface = annotation.getQualifiedName().contentEquals(MAPPER_ANNOTATION);
            for (Element annotated : roundEnv.getElementsAnnotatedWith(annotation)) {
                Element enclosing = onInterface ? annotated : annotated.getEnclosingElement();
                if (enclosing instanceof TypeElement type
                        && type.getKind() == ElementKind.INTERFACE) {
                    mappers.put(type.getQualifiedName().toString(), type);
                } else {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            onInterface ? "@Mapper belongs on an interface"
                                    : "LarkBatis statements belong on interface methods",
                            annotated);
                }
            }
        }
        if (roundEnv.processingOver()) {
            for (String javaType : frontend.unusedTypeHandlerDefaults()) {
                // A registered type nothing in the build ever has is what a
                // typo in the java-type half looks like, and it is otherwise
                // completely silent: no property changes, no error, no handler.
                processingEnv.getMessager().printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                        OPTION_TYPE_HANDLERS + " registers a handler for " + javaType
                                + ", and no property or #{} in this compilation has that type"
                                + " — the entry moved nothing");
            }
            for (Map.Entry<String, MapperXmlParser.XmlMapper> entry : xmlByNamespace.entrySet()) {
                if (!consumedNamespaces.contains(entry.getKey())) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                            entry.getValue().file() + ": namespace " + entry.getKey()
                                    + " matches no compiled @Mapper interface — the file was"
                                    + " ignored. Annotate the interface with @Mapper.");
                }
            }
        }
        if (mappers.isEmpty() && rowClasses.isEmpty()) {
            return false;
        }

        List<MapperModel> models = new ArrayList<>();
        for (TypeElement mapper : mappers.values()) {
            String fqn = mapper.getQualifiedName().toString();
            MapperXmlParser.XmlMapper xml = xmlByNamespace.get(fqn);
            if (xml != null) {
                consumedNamespaces.add(fqn);
            }
            MapperModel model = frontend.parse(mapper, xml);
            if (model == null) {
                continue; // errors already reported; never emit partial code
            }
            models.add(model);
            onModel(model);
            sourceWriter.write(model.packageName() + "." + model.implSimpleName(),
                    MapperImplEmitter.emit(model, resultModels, columnNaming), mapper);
        }

        // Classes the escape hatch reads but no statement returns: nothing put
        // them in resultModels, so @LarkBatisRow does. A class that is also a
        // resultType is already there and keeps that model.
        for (TypeElement rowClass : rowClasses.values()) {
            String fqn = rowClass.getQualifiedName().toString();
            if (resultModels.containsKey(fqn)) {
                continue;
            }
            try {
                resultModels.put(fqn, frontend.rowClass(rowClass));
            } catch (LarkBatisProcessingException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(),
                        e.element() != null ? e.element() : rowClass);
            }
        }

        for (ResultModel result : new TreeMap<>(resultModels).values()) {
            String owner = emittedReaders.putIfAbsent(result.readerFqn(), result.fqn());
            if (owner == null) {
                sourceWriter.write(result.readerFqn(),
                        RowReaderEmitter.emit(result, columnNaming));
            } else if (!owner.equals(result.fqn()) && reportedReaderClashes.add(result.fqn())) {
                // nested classes with the same simple name in one package; the
                // second reader would silently overwrite the first
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Result classes " + owner + " and " + result.fqn()
                                + " both need the row reader " + result.readerFqn()
                                + " — rename one, they cannot share a generated reader");
            }
        }

        if (!models.isEmpty() && !registryEmitted) {
            // emitted in the first round that sees mappers; mappers generated by
            // other processors in later rounds get impls but not registry entries
            registryEmitted = true;
            String registryPackage = processingEnv.getOptions()
                    .getOrDefault(OPTION_REGISTRY_PACKAGE, commonPackage(models));
            sourceWriter.write(registryPackage + "." + RegistryEmitter.REGISTRY_SIMPLE_NAME,
                    RegistryEmitter.emit(registryPackage, models),
                    mappers.values().toArray(new Element[0]));
        }

        if (!models.isEmpty() && !springConfigEmitted && springConfigWanted()) {
            springConfigEmitted = true;
            String configPackage = processingEnv.getOptions()
                    .getOrDefault(OPTION_SPRING_CONFIG_PACKAGE, commonPackage(models));
            sourceWriter.write(
                    configPackage + "." + SpringConfigurationEmitter.CONFIGURATION_SIMPLE_NAME,
                    SpringConfigurationEmitter.emit(configPackage, models),
                    mappers.values().toArray(new Element[0]));
        }
        return false;
    }

    /**
     * Whether to emit the Spring {@code @Configuration}. The
     * trigger is spring-context being on the build classpath — a project that
     * compiles against Spring is a Spring project — and
     * {@code -Alarkbatis.springConfig=false} is the way out for one that
     * wires its mapper beans by hand.
     */
    private boolean springConfigWanted() {
        String option = processingEnv.getOptions().get(OPTION_SPRING_CONFIG);
        if (option != null) {
            return !option.equalsIgnoreCase("false");
        }
        return processingEnv.getElementUtils()
                .getTypeElement(SPRING_CONFIGURATION_ANNOTATION) != null;
    }

    /** Test hook: golden IR assertions override this to capture models. */
    protected void onModel(MapperModel model) {
    }

    /**
     * Scans the {@code -Alarkbatis.mapperDir} directories (path-separator or
     * comma separated) for mapper XML files. This runs on plain java.io — the
     * whole reason the option and the build plugins exist is that
     * Filer.getResource cannot reliably see src/main/resources.
     */
    private Map<String, MapperXmlParser.XmlMapper> parseMapperXml() {
        String option = processingEnv.getOptions().get(OPTION_MAPPER_DIR);
        if (option == null || option.isBlank()) {
            return Map.of();
        }
        Map<String, MapperXmlParser.XmlMapper> byNamespace = new LinkedHashMap<>();
        for (String dir : option.split("," + "|" + java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            Path root = Path.of(dir.trim());
            if (!Files.isDirectory(root)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                        OPTION_MAPPER_DIR + " entry is not a directory: " + root);
                continue;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(p -> p.getFileName().toString().endsWith(".xml"))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (Path file : files) {
                try {
                    MapperXmlParser.XmlMapper mapper = MapperXmlParser.parseIfMapper(file);
                    if (mapper == null) {
                        continue; // some other XML dialect living in the same tree
                    }
                    MapperXmlParser.XmlMapper previous =
                            byNamespace.putIfAbsent(mapper.namespace(), mapper);
                    if (previous != null) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                file + " and " + previous.file() + " both declare namespace "
                                        + mapper.namespace());
                    }
                } catch (MapperXmlParser.NotWellFormedException e) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.MANDATORY_WARNING, e.getMessage());
                } catch (LarkBatisProcessingException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
                }
            }
        }
        return byNamespace;
    }

    private static String commonPackage(List<MapperModel> models) {
        String common = models.get(0).packageName();
        for (MapperModel model : models) {
            String candidate = model.packageName();
            while (!common.isEmpty()
                    && !(candidate.equals(common) || candidate.startsWith(common + "."))) {
                int dot = common.lastIndexOf('.');
                common = dot < 0 ? "" : common.substring(0, dot);
            }
        }
        return common.isEmpty() ? "larkbatis.generated" : common;
    }
}
