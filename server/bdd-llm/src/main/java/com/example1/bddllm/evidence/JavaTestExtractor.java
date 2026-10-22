package com.example1.bddllm.evidence;

import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static, conservative extraction. Source is never compiled or executed.
 */
public final class JavaTestExtractor {
    public static final String VERSION = "java-test-bundles-v1";
    private static final Set<String> TESTS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");
    private static final Set<String> LIFECYCLE = Set.of("BeforeEach", "AfterEach", "BeforeAll", "AfterAll");

    public record Extraction(ArrayNode bundles, ArrayNode warnings) {
    }

    public Extraction extract(ArrayNode documents) {
        ArrayNode bundles = Json.array(), warnings = Json.array();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17).setTabSize(1));
        for (JsonNode document : documents) {
            if (!document.path("kind").asText().equals("test")) continue;
            String path = document.path("path").asText(), source = document.path("content").asText();
            ParseResult<CompilationUnit> parsed = parser.parse(source);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                warnings.add(path + ": parse_failed; 이 파일의 테스트는 판단하지 못했습니다.");
                continue;
            }
            CompilationUnit unit = parsed.getResult().orElseThrow();
            int count = 0;
            for (MethodDeclaration test : unit.findAll(MethodDeclaration.class)) {
                if (test.getAnnotations().stream().noneMatch(annotation -> TESTS.contains(simple(annotation)))) continue;
                count++;
                List<TypeDeclaration<?>> owners = owners(test);
                Map<String, ObjectNode> chunks = new LinkedHashMap<>();
                ArrayNode issues = Json.array();
                unit.getPackageDeclaration().ifPresent(packageDeclaration -> add(chunks, chunk(path, source, packageDeclaration, "package")));
                unit.getImports().forEach(importDeclaration -> add(chunks, chunk(path, source, importDeclaration, "import")));
                Set<MethodDeclaration> included = new HashSet<>();
                for (TypeDeclaration<?> owner : owners) {
                    Range range = owner.getRange().orElseThrow();
                    int begin = offset(source, range.begin.line, range.begin.column);
                    int brace = source.indexOf('{', offset(source, owner.getName().getEnd().orElseThrow().line,
                        owner.getName().getEnd().orElseThrow().column));
                    if (brace >= begin) add(chunks, slice(path, source, begin, brace + 1, "class_context"));
                    for (BodyDeclaration<?> member : owner.getMembers()) {
                        if (member.isFieldDeclaration() || member.isInitializerDeclaration())
                            add(chunks, chunk(path, source, member, "field_or_initializer"));
                        if (member instanceof MethodDeclaration method && method.getAnnotations().stream().anyMatch(annotation -> LIFECYCLE.contains(simple(annotation))))
                            includeMethod(method, owners, included);
                    }
                    if (owner.isClassOrInterfaceDeclaration() && !owner.asClassOrInterfaceDeclaration().getExtendedTypes().isEmpty())
                        issues.add("상속된 준비 코드·helper는 자동 해석하지 않습니다: " + owner.getNameAsString());
                    // Preserve nested fixtures defined in the same test file, excluding nested test suites.
                    for (BodyDeclaration<?> member : owner.getMembers())
                        if (member instanceof TypeDeclaration<?> nested && nested.findAll(MethodDeclaration.class).stream()
                            .noneMatch(method -> method.getAnnotations().stream().anyMatch(annotation -> TESTS.contains(simple(annotation)))))
                            add(chunks, chunk(path, source, nested, "nested_fixture"));
                }
                includeMethod(test, owners, included);
                for (AnnotationExpr annotation : test.getAnnotations()) {
                    if (simple(annotation).equals("MethodSource")) {
                        String annotationText = annotation.toString();
                        boolean found = false;
                        for (TypeDeclaration<?> owner : owners)
                            for (MethodDeclaration method : owner.getMethods())
                                if (annotationText.contains("\"" + method.getNameAsString() + "\"")
                                    || annotationText.equals("@MethodSource") && method.getNameAsString().equals(test.getNameAsString())) {
                                    includeMethod(method, owners, included);
                                    found = true;
                                }
                        if (!found) issues.add("MethodSource 제공자를 연결하지 못했습니다.");
                    }
                    if (Set.of("CsvFileSource", "ArgumentsSource").contains(simple(annotation)))
                        issues.add("외부 테스트 입력 제공자 또는 리소스는 실행·수집하지 않았습니다.");
                }
                included.stream().sorted(java.util.Comparator.comparingInt(method -> method.getBegin().orElseThrow().line))
                    .forEach(method -> add(chunks, chunk(path, source, method, method == test ? "test" : "setup_or_helper")));
                // Explicit support files are indivisible dependency units. No name-based guess drops a helper.
                for (JsonNode support : documents)
                    if (support.path("kind").asText().equals("support"))
                        add(chunks, slice(support.path("path").asText(), support.path("content").asText(),
                            0, support.path("content").asText().length(), "support_file"));
                boolean disabled = test.getAnnotationByName("Disabled").isPresent()
                    || owners.stream().anyMatch(owner -> owner.getAnnotationByName("Disabled").isPresent());
                if (disabled) issues.add("Disabled 지정: 실행되는 테스트라고 판단하지 마세요.");
                issues.add("정적 수집 범위: 외부 라이브러리·미지정 파일·동적 의존성의 해석과 실제 실행 여부는 확인하지 않았습니다.");
                ArrayNode values = Json.array();
                chunks.values().forEach(values::add);
                String id = path + "#" + test.getNameAsString() + ":" + test.getBegin().orElseThrow().line;
                bundles.add(Json.obj("id", id, "path", path, "method", test.getNameAsString(), "start_line", test.getBegin().orElseThrow().line,
                    "disabled", disabled, "issues", issues, "chunks", values));
            }
            if (count == 0) warnings.add(path + ": recognized_tests_not_found; 지원하지 않는 테스트 형식일 수 있습니다.");
        }
        return new Extraction(bundles, warnings);
    }

    private static String simple(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier();
    }

    private static List<TypeDeclaration<?>> owners(MethodDeclaration test) {
        List<TypeDeclaration<?>> result = new ArrayList<>();
        Node current = test.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> declaration) result.addFirst(declaration);
            current = current.getParentNode().orElse(null);
        }
        return result;
    }

    private static void includeMethod(MethodDeclaration method, List<TypeDeclaration<?>> owners, Set<MethodDeclaration> included) {
        if (!included.add(method)) return;
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class))
            for (TypeDeclaration<?> owner : owners)
                for (MethodDeclaration helper : owner.getMethodsByName(call.getNameAsString()))
                    if (helper.getParameters().size() == call.getArguments().size())
                        includeMethod(helper, owners, included);
    }

    private static void add(Map<String, ObjectNode> chunks, ObjectNode chunk) {
        chunks.putIfAbsent(chunk.path("id").asText(), chunk);
    }

    private static ObjectNode chunk(String path, String source, Node node, String kind) {
        Range range = node.getRange().orElseThrow();
        return slice(path, source, offset(source, range.begin.line, range.begin.column),
            offset(source, range.end.line, range.end.column) + 1, kind);
    }

    public static int offset(String source, int line, int column) {
        int offset = 0;
        for (int lineNumber = 1; lineNumber < line; lineNumber++) {
            offset = source.indexOf('\n', offset);
            if (offset < 0) throw new IllegalArgumentException("invalid_source_range");
            offset++;
        }
        // JavaParser counts a UTF-16 tab as one column with its default tab size of 1.
        int end = offset + column - 1;
        if (column < 1 || end > source.length()) throw new IllegalArgumentException("invalid_source_range");
        return end;
    }

    private static ObjectNode slice(String path, String source, int begin, int end, String kind) {
        String text = source.substring(begin, end);
        int startLine = 1, endLine = 1, startColumn = 1, endColumn = 1;
        for (int characterIndex = 0; characterIndex < end; characterIndex++) {
            if (characterIndex == begin) {
                startLine = endLine;
                startColumn = endColumn;
            }
            if (characterIndex == end - 1) break;
            if (source.charAt(characterIndex) == '\n') {
                endLine++;
                endColumn = 1;
            } else endColumn++;
        }
        return Json.obj("id", Json.sha(path + ":" + begin + ":" + end + ":" + text), "kind", kind, "path", path,
            "start_line", startLine, "end_line", endLine, "start_column", startColumn, "end_column", endColumn,
            "content", text, "sha256", Json.sha(text));
    }
}
