package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class OwRepositoryTest {
    private String run(String source) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(buffer)).run(
                new Parser(new Lexer(source, "repository.ow").lexAll()).parseProgram());
        return buffer.toString();
    }

    @Test public void repositoriesAreSingletonsAndCanBeInjected() {
        String result = run("""
                @Repository("app_users", "id")
                class UserRepository:
                  # Generated CRUD methods require the Night platform at invocation.

                @Service
                class UserService:
                  @Inject
                  field private users UserRepository

                  fn repository() -> UserRepository:
                    return: this.users

                main:
                  set userRepo: component_get("UserRepository")
                  set service: component_get("UserService")
                  call: assert(userRepo == service.repository(), "injected singleton")
                  call: assert(len(component_scan("Repository")) == 1)
                  call: assert(len(component_scan("All")) == 2)
                  print: "PASS repository singleton injection"
                """);
        assertTrue(result, result.contains("PASS repository singleton injection"));
    }

    @Test public void rejectsUnsafeRepositoryIdentifiersAndInvalidUsage() {
        for (String source : new String[] {
                "@Repository(\"app_users; DROP TABLE app_users\", \"id\")\nclass Repo:\n  field ignored\n",
                "@Repository(\"users\", \"id DESC\")\nclass Repo:\n  field ignored\n",
                "@Repository(\"users\", \"id\")\nclass Repo:\n  field forbidden\n",
                "@Repository(\"users\", \"id\")\nclass Repo:\n  fn custom():\n    return: 1\n",
                "@Repository(\"users\", \"id\")\nclass Repo(Other):\n  field value\n",
                "@Repository(\"users\", \"id\")\nfn not_a_class():\n  return: 1\n",
                "@Repository(\"users\", \"id\")\n@Controller\nclass TwoStereotypes:\n  field value\n"
        }) {
            assertThrows(RuntimeException.class, () ->
                    new Parser(new Lexer(source, "invalid-repository.ow").lexAll()).parseProgram());
        }
    }

    @Test public void failsClosedWhenNightRepositoryRuntimeIsMissing() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                @Repository("users", "id")
                class UserRepository:
                main:
                  set repo: component_get("UserRepository")
                  call: repo.findAll()
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("requires Night"));
    }

    @Test public void rejectsUnknownGeneratedRepositoryMethods() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                @Repository("users", "id")
                class UserRepository:
                main:
                  set repo: component_get("UserRepository")
                  call: repo.findByEmail("x")
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("Unknown repository operation"));
    }

    @Test public void rejectsInvalidArityBeforeReachingDatabase() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                @Repository("users", "id")
                class UserRepository:
                main:
                  set repo: component_get("UserRepository")
                  call: repo.page("id", 5)
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("expects 3 argument"));
    }
}
