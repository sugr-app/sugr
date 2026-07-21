package com.sugr.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Recursively turns a {@link TypeMirror} into: a Java expression that decodes
 * a {@code com.sugr.bridge.Json} value into that type, an expression that
 * encodes a value of that type back into a {@code Json}, and its TS type.
 * Composite types (record, List, Map) are handled via generated static
 * helper methods (collected in {@link #helperMethodsSource()}) so nested
 * types compose without inlining deeply nested expressions.
 *
 * Supported: String, primitives/boxed numerics, boolean, records (nested),
 * List&lt;T&gt;, Map&lt;String,T&gt; - matches plan.md Milestone 3.1's spec.
 *
 * Each kind's decode/encode/tsType behavior is defined together in one place
 * ({@link #opsFor}) rather than three separate switches that would otherwise
 * need to stay in lockstep whenever a new kind is added.
 */
final class TypeCodec {

    private final ProcessingEnvironment env;
    private final Types types;
    private final Map<String, String> helperNames = new LinkedHashMap<>();
    private final List<String> helperSource = new ArrayList<>();
    private final Map<String, String> tsInterfaces = new LinkedHashMap<>();

    TypeCodec(ProcessingEnvironment env) {
        this.env = env;
        this.types = env.getTypeUtils();
    }

    List<String> helperMethodsSource() {
        return helperSource;
    }

    Map<String, String> tsInterfaceSource() {
        return tsInterfaces;
    }

    /** Java expression of type Json.* decoded into `type`; jsonExpr must be a Json-typed expression. */
    String decode(TypeMirror type, String jsonExpr) {
        return opsFor(type).decode().apply(jsonExpr);
    }

    /** Java expression producing a Json from a value of type `type`. */
    String encode(TypeMirror type, String valueExpr) {
        return opsFor(type).encode().apply(valueExpr);
    }

    String javaTypeName(TypeMirror type) {
        return type.toString();
    }

    String tsType(TypeMirror type) {
        return opsFor(type).tsType().get();
    }

    /** True if `type` is CompletableFuture&lt;T&gt;; the bound method is async in that case. */
    boolean isCompletableFuture(TypeMirror type) {
        return erasureIs(type, "java.util.concurrent.CompletableFuture");
    }

    TypeMirror completableFutureInner(TypeMirror type) {
        return ((DeclaredType) type).getTypeArguments().get(0);
    }

    private enum Kind {STRING, INT, LONG, DOUBLE, BOOLEAN, RECORD, LIST, MAP}

    /** decode/encode/tsType for one Kind, computed together so adding a type touches one switch arm. */
    private record TypeOps(Function<String, String> decode, Function<String, String> encode, Supplier<String> tsType) {
    }

    private TypeOps opsFor(TypeMirror type) {
        Kind kind = classify(type);
        return switch (kind) {
            case STRING -> new TypeOps(
                    expr -> expr + ".asString()",
                    expr -> "com.sugr.bridge.Json.of(" + expr + ")",
                    () -> "string");
            case INT -> new TypeOps(
                    expr -> "(int) (" + expr + ").asNumber()",
                    expr -> "com.sugr.bridge.Json.of((long) (" + expr + "))",
                    () -> "number");
            case LONG -> new TypeOps(
                    expr -> "(long) (" + expr + ").asNumber()",
                    expr -> "com.sugr.bridge.Json.of((long) (" + expr + "))",
                    () -> "number");
            case DOUBLE -> new TypeOps(
                    expr -> "(" + expr + ").asNumber()",
                    expr -> "com.sugr.bridge.Json.of((double) (" + expr + "))",
                    () -> "number");
            case BOOLEAN -> new TypeOps(
                    expr -> "(" + expr + ").asBoolean()",
                    expr -> "com.sugr.bridge.Json.of((boolean) (" + expr + "))",
                    () -> "boolean");
            case RECORD -> {
                DeclaredType dt = (DeclaredType) type;
                yield new TypeOps(
                        expr -> ensureRecordHelpers(dt) + "_decode(" + expr + ")",
                        expr -> ensureRecordHelpers(dt) + "_encode(" + expr + ")",
                        () -> {
                            TypeElement el = (TypeElement) dt.asElement();
                            registerTsInterface(el);
                            return el.getSimpleName().toString();
                        });
            }
            case LIST -> {
                DeclaredType dt = (DeclaredType) type;
                TypeMirror element = dt.getTypeArguments().get(0);
                yield new TypeOps(
                        expr -> ensureListHelpers(dt) + "_decode(" + expr + ")",
                        expr -> ensureListHelpers(dt) + "_encode(" + expr + ")",
                        () -> tsType(element) + "[]");
            }
            case MAP -> {
                DeclaredType dt = (DeclaredType) type;
                TypeMirror value = dt.getTypeArguments().get(1);
                yield new TypeOps(
                        expr -> ensureMapHelpers(dt) + "_decode(" + expr + ")",
                        expr -> ensureMapHelpers(dt) + "_encode(" + expr + ")",
                        () -> "Record<string, " + tsType(value) + ">");
            }
        };
    }

    private Kind classify(TypeMirror type) {
        if (type.getKind() == TypeKind.INT) return Kind.INT;
        if (type.getKind() == TypeKind.LONG) return Kind.LONG;
        if (type.getKind() == TypeKind.DOUBLE) return Kind.DOUBLE;
        if (type.getKind() == TypeKind.BOOLEAN) return Kind.BOOLEAN;

        String name = types.erasure(type).toString();
        if (name.equals("java.lang.String")) return Kind.STRING;
        if (name.equals("java.lang.Integer")) return Kind.INT;
        if (name.equals("java.lang.Long")) return Kind.LONG;
        if (name.equals("java.lang.Double")) return Kind.DOUBLE;
        if (name.equals("java.lang.Boolean")) return Kind.BOOLEAN;
        if (name.equals("java.util.List")) return Kind.LIST;
        if (name.equals("java.util.Map")) return Kind.MAP;

        if (type instanceof DeclaredType dt && dt.asElement().getKind() == ElementKind.RECORD) {
            return Kind.RECORD;
        }
        throw new IllegalArgumentException("Unsupported @Bind type: " + type
                + " (supported: String, int/long/double/boolean, records, List<T>, Map<String,T>, CompletableFuture<T> as a return type)");
    }

    private boolean erasureIs(TypeMirror type, String fqn) {
        return types.erasure(type).toString().equals(fqn);
    }

    /**
     * Handles the "already generated this helper pair? return its prefix; else compute
     * a prefix, build+register the decode/encode source, cache it" bookkeeping shared by
     * ensureRecordHelpers/ensureListHelpers/ensureMapHelpers - each just supplies its own
     * prefix-naming and decode/encode source text.
     */
    private String memoizedHelper(String key, Supplier<String> prefixSupplier, Function<String, List<String>> sourcesFor) {
        String cached = helperNames.get(key);
        if (cached != null) return cached;
        String prefix = prefixSupplier.get();
        helperNames.put(key, prefix);
        helperSource.addAll(sourcesFor.apply(prefix));
        return prefix;
    }

    private String ensureRecordHelpers(DeclaredType type) {
        TypeElement el = (TypeElement) type.asElement();
        String key = "record:" + el.getQualifiedName();
        return memoizedHelper(key, () -> "__" + el.getSimpleName(), prefix -> {
            List<? extends RecordComponentElement> components = el.getRecordComponents();

            StringBuilder decode = new StringBuilder();
            decode.append("private static ").append(el.getQualifiedName()).append(' ').append(prefix)
                    .append("_decode(com.sugr.bridge.Json __json) {\n");
            decode.append("    java.util.Map<String, com.sugr.bridge.Json> __f = __json.asObject();\n");
            decode.append("    return new ").append(el.getQualifiedName()).append("(\n");
            for (int i = 0; i < components.size(); i++) {
                RecordComponentElement c = components.get(i);
                String fieldJson = "__f.get(\"" + c.getSimpleName() + "\")";
                decode.append("        ").append(decode(c.asType(), fieldJson));
                if (i < components.size() - 1) decode.append(',');
                decode.append('\n');
            }
            decode.append("    );\n}");

            StringBuilder encode = new StringBuilder();
            encode.append("private static com.sugr.bridge.Json ").append(prefix)
                    .append("_encode(").append(el.getQualifiedName()).append(" __v) {\n");
            encode.append("    java.util.Map<String, com.sugr.bridge.Json> __m = new java.util.LinkedHashMap<>();\n");
            for (RecordComponentElement c : components) {
                String accessor = "__v." + c.getSimpleName() + "()";
                encode.append("    __m.put(\"").append(c.getSimpleName()).append("\", ")
                        .append(encode(c.asType(), accessor)).append(");\n");
            }
            encode.append("    return com.sugr.bridge.Json.object(__m);\n}");

            return List.of(decode.toString(), encode.toString());
        });
    }

    private String ensureListHelpers(DeclaredType type) {
        TypeMirror element = type.getTypeArguments().get(0);
        String key = "list:" + element;
        return memoizedHelper(key, () -> "__list" + (helperNames.size() + 1), prefix -> {
            String elementJavaType = javaTypeName(element);

            StringBuilder decode = new StringBuilder();
            decode.append("private static java.util.List<").append(elementJavaType).append("> ").append(prefix)
                    .append("_decode(com.sugr.bridge.Json __json) {\n");
            decode.append("    java.util.List<").append(elementJavaType).append("> __out = new java.util.ArrayList<>();\n");
            decode.append("    for (com.sugr.bridge.Json __e : __json.asArray()) {\n");
            decode.append("        __out.add(").append(decode(element, "__e")).append(");\n");
            decode.append("    }\n    return __out;\n}");

            StringBuilder encode = new StringBuilder();
            encode.append("private static com.sugr.bridge.Json ").append(prefix)
                    .append("_encode(java.util.List<").append(elementJavaType).append("> __v) {\n");
            encode.append("    java.util.List<com.sugr.bridge.Json> __out = new java.util.ArrayList<>();\n");
            encode.append("    for (").append(elementJavaType).append(" __e : __v) {\n");
            encode.append("        __out.add(").append(encode(element, "__e")).append(");\n");
            encode.append("    }\n    return com.sugr.bridge.Json.array(__out);\n}");

            return List.of(decode.toString(), encode.toString());
        });
    }

    private String ensureMapHelpers(DeclaredType type) {
        TypeMirror value = type.getTypeArguments().get(1);
        String key = "map:" + value;
        return memoizedHelper(key, () -> "__map" + (helperNames.size() + 1), prefix -> {
            String valueJavaType = javaTypeName(value);

            StringBuilder decode = new StringBuilder();
            decode.append("private static java.util.Map<String, ").append(valueJavaType).append("> ").append(prefix)
                    .append("_decode(com.sugr.bridge.Json __json) {\n");
            decode.append("    java.util.Map<String, ").append(valueJavaType).append("> __out = new java.util.LinkedHashMap<>();\n");
            decode.append("    for (var __e : __json.asObject().entrySet()) {\n");
            decode.append("        __out.put(__e.getKey(), ").append(decode(value, "__e.getValue()")).append(");\n");
            decode.append("    }\n    return __out;\n}");

            StringBuilder encode = new StringBuilder();
            encode.append("private static com.sugr.bridge.Json ").append(prefix)
                    .append("_encode(java.util.Map<String, ").append(valueJavaType).append("> __v) {\n");
            encode.append("    java.util.Map<String, com.sugr.bridge.Json> __out = new java.util.LinkedHashMap<>();\n");
            encode.append("    for (var __e : __v.entrySet()) {\n");
            encode.append("        __out.put(__e.getKey(), ").append(encode(value, "__e.getValue()")).append(");\n");
            encode.append("    }\n    return com.sugr.bridge.Json.object(__out);\n}");

            return List.of(decode.toString(), encode.toString());
        });
    }

    private void registerTsInterface(TypeElement el) {
        String name = el.getSimpleName().toString();
        if (tsInterfaces.containsKey(name)) return;
        tsInterfaces.put(name, null); // reserve the slot before recursing, in case of self-reference
        StringBuilder ts = new StringBuilder();
        ts.append("export interface ").append(name).append(" {\n");
        for (RecordComponentElement c : el.getRecordComponents()) {
            ts.append("  ").append(c.getSimpleName()).append(": ").append(tsType(c.asType())).append('\n');
        }
        ts.append("}");
        tsInterfaces.put(name, ts.toString());
    }
}
