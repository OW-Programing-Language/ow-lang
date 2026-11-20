package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class OwRouteTransactionAnnotationsTest {
    private String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
                new Parser(new Lexer(source, "routes.ow").lexAll()).parseProgram());
        return output.toString();
    }

    @Test public void mapsHttpMethodsWithoutManualMount() {
        String result = run("""
                @Controller
                class Books:
                  @GetMapping("/api/books")
                  fn list() -> int:
                    return: 2

                  @PostMapping("/api/books")
                  fn create() -> int:
                    return: 1

                main:
                  set books: component_get("Books")
                  call: assert(component_mount(books) == 2)
                  print: "PASS annotated routes"
                """);
        assertTrue(result, result.contains("PASS annotated routes"));
    }

    @Test public void rejectsDuplicateRoutesBeforeExecutingMain() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                @Controller
                class First:
                  @GetMapping("/api/books")
                  fn list() -> int:
                    return: 1
                @Controller
                class Second:
                  @GetMapping("/api/books")
                  fn other() -> int:
                    return: 2
                main:
                  print: "SHOULD NOT EXECUTE"
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("Duplicate annotated OW route"));
    }

    @Test public void rejectsInvalidRouteAnnotationsAndMethodSignatures() {
        for (String input : new String[] {
                """
                @Service
                class Invalid:
                  @GetMapping("/bad")
                  fn endpoint() -> int:
                    return: 1
                """,
                """
                @Controller
                class Invalid:
                  @PostMapping("/bad")
                  fn endpoint(v int) -> int:
                    return: v
                """,
                """
                @Controller
                class Invalid:
                  @GetMapping("/bad")
                  fn private endpoint() -> int:
                    return: 1
                """
        }) {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> run(input + "main:\n  print: \"SHOULD NOT EXECUTE\"\n"));
            assertNotNull(ex.getMessage());
        }
    }

    @Test public void rejectsMalformedPathsAndMappingAnnotationsOnFunctions() {
        for (String source : new String[] {
                "@Controller\nclass X:\n  @GetMapping(\"relative\")\n  fn x():\n    return: 1\n",
                "@Controller\nclass X:\n  @GetMapping(\"/a?x=1\")\n  fn x():\n    return: 1\n",
                "@GetMapping(\"/a\")\nfn x():\n  return: 1\n",
                "@Controller\nclass X:\n  @GetMapping(\"/a\")\n  @PostMapping(\"/b\")\n  fn x():\n    return: 1\n"
        }) {
            assertThrows(RuntimeException.class, () ->
                    new Parser(new Lexer(source, "invalid-routes.ow").lexAll()).parseProgram());
        }
    }

    @Test public void transactionalAnnotationRequiresAnInstalledJDBCAdapter() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                @Service
                class Orders:
                  @Transactional
                  fn save() -> int:
                    return: 1
                main:
                  set service: component_get("Orders")
                  print: service.save()
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("@Transactional requires the Night JDBC runtime extension"));
    }

    @Test public void allowsStackingMappingAndTransactional() {
        String output = run("""
                @Controller
                class Endpoint:
                  @PostMapping("/items")
                  @Transactional
                  fn create() -> int:
                    return: 1
                main:
                  set ctrl: component_get("Endpoint")
                  call: assert(component_mount(ctrl) == 1)
                  print: "PASS transactional mapping"
                """);
        assertTrue(output, output.contains("PASS transactional mapping"));
    }

    @Test public void rejectsMappingMixedWithLegacyMount() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                @Controller
                class DuplicateStyle:
                  @GetMapping("/items")
                  fn index() -> int:
                    return: 1
                  fn mount() -> int:
                    return: 1
                main:
                  set ctrl: component_get("DuplicateStyle")
                  call: component_mount(ctrl)
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("cannot mix"));
    }
}
