package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class OwComponentsTest {
    private String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
                new Parser(new Lexer(source, "components.ow").lexAll()).parseProgram());
        return output.toString();
    }

    @Test public void discoversTypedSingletonServicesAndControllers() {
        String actual = run("""
                @Service
                class Repository:
                  fn value() -> int:
                    return: 7
                @Service
                class PriceService:
                  @Inject
                  field private repository Repository
                  fn total() -> int:
                    return: this.repository.value() * 2
                @Controller
                class PriceController:
                  @Inject
                  field private prices PriceService
                  fn get() -> int:
                    return: this.prices.total()
                main:
                  set controllers: component_scan("Controller")
                  call: assert(len(controllers) == 1)
                  call: assert(controllers[0].get() == 14)
                  set services: component_scan("Service")
                  call: assert(len(services) == 2)
                  set all: component_scan("All")
                  call: assert(len(all) == 3)
                  set same: component_get("PriceController")
                  call: assert(same == controllers[0], "singleton identity")
                  call: assert(same.get() == 14, "injected controller works")
                  print: "PASS components"
                """);
        assertTrue(actual, actual.contains("PASS components"));
    }

    @Test public void rejectsMissingProviderBeforeExecutingMain() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                class NotAService:
                  fn get() -> int:
                    return: 3
                @Service
                class BrokenService:
                  @Inject
                  field provider NotAService
                main:
                  print: "SHOULD NOT RUN"
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("Missing component NotAService"));
    }

    @Test public void rejectsCircularDependencies() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                @Service
                class A:
                  @Inject
                  field b B
                @Service
                class B:
                  @Inject
                  field a A
                main:
                  print: "SHOULD NOT RUN"
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("component dependency cycle"));
    }

    @Test public void rejectsInjectedFieldsOnPlainClasses() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                @Service
                class Provider:
                  fn value() -> int:
                    return: 1
                class Consumer:
                  @Inject
                  field provider Provider
                main:
                  print: "SHOULD NOT RUN"
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("@Inject requires"));
    }

    @Test public void rejectsInvalidAnnotationPlacementsAndUntypedInjection() {
        for (String source : new String[]{
                "@Service\nfn wrong():\n  return: 1\n",
                "@Inject\nclass Wrong:\n  field value\n",
                "class Wrong:\n  @Controller\n  fn test():\n    return: 1\n",
                "@Service\nclass Wrong:\n  @Inject\n  field value\n",
                "@Service\nclass Wrong:\n  @Inject\n  field other string: \"bad\"\n"
        }) {
            assertThrows(RuntimeException.class, () ->
                    new Parser(new Lexer(source, "bad-component.ow").lexAll()).parseProgram());
        }
    }

    @Test public void scanCannotInstantiatePlainClassesAndInvalidCategoriesFail() {
        RuntimeException notComponent = assertThrows(RuntimeException.class, () -> run("""
                class Normal:
                  fn value() -> int:
                    return: 1
                main:
                  set bad: component_get("Normal")
                """));
        assertTrue(notComponent.getMessage(), notComponent.getMessage().contains("Unknown OW component"));
        RuntimeException badCategory = assertThrows(RuntimeException.class, () -> run("""
                main:
                  set bad: component_scan("Everything")
                """));
        assertTrue(badCategory.getMessage(), badCategory.getMessage().contains("component_scan requires"));
    }
}
