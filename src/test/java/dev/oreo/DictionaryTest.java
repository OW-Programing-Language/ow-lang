package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

/** Native dictionary / map support: literals, dynamic indexing and map_* builtins. */
public class DictionaryTest {
    private String run(String program) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
                new Parser(new Lexer(program).lexAll()).parseProgram());
        return output.toString().replace("\r\n", "\n");
    }

    @Test public void emptyDictionaryHasSizeZero() {
        assertEquals("0\n", run("main:\n  set d: {}\n  print: map_size(d)\n"));
    }

    @Test public void literalWithQuotedKeysContainingSpaces() {
        String out = run("""
                main:
                  set sprites: {
                    "Blaze": "hostile/blaze.gif",
                    "Magma Cube": "hostile/magmacube.png",
                    "Zombie": "hostile/zombie.png"
                  }
                  print: sprites["Magma Cube"]
                  print: map_size(sprites)
                """);
        assertEquals("hostile/magmacube.png\n3\n", out);
    }

    @Test public void dynamicStringKeyReadFromVariable() {
        String out = run("""
                main:
                  set sprites: { "Blaze": "hostile/blaze.gif" }
                  set name: "Blaze"
                  print: sprites[name]
                """);
        assertEquals("hostile/blaze.gif\n", out);
    }

    @Test public void insertAndUpdateThroughIndexAssignment() {
        String out = run("""
                main:
                  set m: {}
                  set m["Zombie"]: "z1"
                  set key: "Creeper"
                  set m[key]: "c1"
                  print: m["Zombie"]
                  set m["Zombie"]: "z2"
                  print: m["Zombie"]
                  print: map_size(m)
                """);
        assertEquals("z1\nz2\n2\n", out);
    }

    @Test public void mapGetReturnsFallbackOnlyWhenKeyAbsent() {
        String out = run("""
                main:
                  set m: { "Zombie": "z" }
                  print: map_get(m, "Zombie", "fallback")
                  print: map_get(m, "Missing", "fallback")
                """);
        assertEquals("z\nfallback\n", out);
    }

    @Test public void falsyStoredValuesArePreserved() {
        String out = run("""
                main:
                  set v: { "zero": 0, "disabled": false, "empty": "" }
                  print: map_get(v, "zero", 100)
                  print: map_get(v, "disabled", true)
                  print: map_get(v, "empty", "DEFAULT")
                  print: map_get(v, "missing", 42)
                """);
        assertEquals("0\nfalse\n\n42\n", out);
    }

    @Test public void storedNullIsNotTreatedAsAbsent() {
        String out = run("""
                main:
                  set v: map_set({}, "nothing", json_parse("null"))
                  print: map_has(v, "nothing")
                  print: map_get(v, "nothing", 42)
                """);
        assertEquals("true\nnull\n", out);
    }

    @Test public void hasDeleteKeysValuesSize() {
        String out = run("""
                main:
                  set m: { "a": 1, "b": 2 }
                  print: map_has(m, "a")
                  set m: map_delete(m, "a")
                  print: map_has(m, "a")
                  print: map_keys(m)
                  print: map_values(m)
                  print: map_size(m)
                """);
        assertEquals("true\nfalse\n[b]\n[2]\n1\n", out);
    }

    @Test public void nestedDictionaryAccess() {
        String out = run("""
                main:
                  set cfg: { "mobs": { "Blaze": { "hp": 20 } } }
                  print: cfg["mobs"]["Blaze"]["hp"]
                """);
        assertEquals("20\n", out);
    }

    @Test public void jsonRoundTripPreservesEntries() {
        String out = run("""
                main:
                  set m: { "Magma Cube": "x", "Zombie": "z" }
                  set back: json_parse(json(m))
                  print: map_get(back, "Magma Cube", "?")
                """);
        assertEquals("x\n", out);
    }

    @Test public void objectLiteralsWithBareKeysStillWork() {
        String out = run("""
                main:
                  set o: { name: "Steve", level: 5 }
                  print: o.name
                  print: o["level"]
                """);
        assertEquals("Steve\n5\n", out);
    }

    @Test public void missingKeyIndexRaisesClearError() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                main:
                  set m: { "a": 1 }
                  print: m["b"]
                """));
        assertTrue(e.getMessage().contains("no key 'b'"));
    }

    @Test public void nonStringKeyIsRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                main:
                  set m: { "a": 1 }
                  print: m[5]
                """));
        assertTrue(e.getMessage().contains("keys must be strings"));
    }

    @Test public void mapGetWithWrongArgumentCountIsRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                main:
                  set m: { "a": 1 }
                  print: map_get(m, "a")
                """));
        assertNotNull(e.getMessage());
    }

    @Test public void mapGetDoesNotEvaluateFallbackWhenKeyPresent() {
        // If the fallback were evaluated eagerly, dividing by zero would throw.
        String out = run("""
                main:
                  set m: { "a": 7 }
                  print: map_get(m, "a", 1 / 0)
                """);
        assertEquals("7\n", out);
    }
}
