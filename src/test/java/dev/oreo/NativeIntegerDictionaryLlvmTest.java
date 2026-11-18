package dev.oreo;

import org.junit.Test;

import static org.junit.Assert.*;

/** Native map subset: reference-counted UTF-8 keys and integer values. */
public class NativeIntegerDictionaryLlvmTest {
    private String ir(String source) {
        Ast.Program ast = new Parser(new Lexer(source, "intmap.ow").lexAll()).parseProgram();
        return new LlvmIrGen().generate(ast, "intmap_test");
    }

    @Test public void literalWithQuotedAndBareKeysCompiles() {
        String ll = ir("""
                main:
                  set ratings: { "Magma Cube": 21, Zombie: 7 }
                  print: ratings["Magma Cube"]
                  print: map_size(ratings)
                """);
        assertTrue(ll.contains("%ow.intmap.entry = type { %ow.string, i32, %ow.intmap.entry* }"));
        assertTrue(ll.contains("%ow.intmap = type { %ow.intmap.entry**, i32, i32, i32 }"));
        assertTrue(ll.contains("call %ow.intmap* @__ow_native_intmap_new()"));
        assertTrue(ll.contains("call void @__ow_native_intmap_set(%ow.intmap*"));
        assertTrue(ll.contains("call i32 @__ow_native_intmap_get(%ow.intmap*"));
    }

    @Test public void dynamicUnicodeKeyUsesStringOwnership() {
        String ll = ir("""
                main:
                  set key: "é" + "té"
                  set values: {}
                  set values[key]: 7
                  print: values["été"]
                """);
        assertTrue(ll.contains("call %ow.string @__ow_native_string_concat("));
        assertTrue(ll.contains("call void @__ow_native_string_retain(%ow.string"));
        assertTrue(ll.contains("call void @__ow_native_string_release(%ow.string %key)"));
    }

    @Test public void aliasesAndReassignmentRetainAndReleaseMap() {
        String ll = ir("""
                main:
                  set original: { key: 2 }
                  set alias: original
                  set original: {}
                  print: alias["key"]
                """);
        assertTrue(ll.contains("call void @__ow_native_intmap_retain(%ow.intmap*"));
        assertTrue(ll.contains("call void @__ow_native_intmap_release(%ow.intmap*"));
        assertTrue(ll.contains("define void @__ow_native_intmap_release("));
        assertTrue(ll.contains("call void @free(i8* %rawNode)"));
        assertTrue(ll.contains("call void @free(i8* %rawMap)"));
    }

    @Test public void mapGetUsesConditionalFallbackBranch() {
        String ll = ir("""
                fn fallback():
                  return: 5
                main:
                  set values: { a: 3 }
                  print: map_get(values, "a", fallback())
                """);
        int main = ll.indexOf("define i32 @main()");
        String mainIr = ll.substring(main);
        int condition = mainIr.indexOf("intmap.get.present.");
        int absent = mainIr.indexOf("intmap.get.absent.");
        int fallback = mainIr.indexOf("call i32 @fallback()");
        assertTrue(condition > 0 && absent > 0 && fallback > absent);
        assertTrue(mainIr.contains("intmap.get.end."));
    }

    @Test public void mapSetAndDeleteReturnOriginalMap() {
        String ll = ir("""
                main:
                  set m: map_set({}, "a", 3)
                  set alias: m
                  set m: map_delete(m, "a")
                  print: map_size(alias)
                """);
        assertTrue(ll.contains("call void @__ow_native_intmap_delete(%ow.intmap*"));
        assertTrue(ll.contains("call void @__ow_native_intmap_set(%ow.intmap*"));
        assertTrue(ll.contains("call i32 @__ow_native_intmap_size(%ow.intmap*"));
    }

    @Test public void hasReturnsBooleanAndPrintsWordsNotIntegers() {
        String ll = ir("""
                main:
                  set m: { foo: 1 }
                  print: map_has(m, "foo")
                """);
        assertTrue(ll.contains("call i1 @__ow_native_intmap_has(%ow.intmap*"));
        assertTrue(ll.contains("@.bool_true"));
        assertTrue(ll.contains("@.bool_false"));
        assertTrue(ll.contains(" = select i1 "));
    }

    @Test public void typedFunctionArgumentsAndReturnsUseIntMapPointer() {
        String ll = ir("""
                fn make():
                  returns: int_map
                  return: { a: 3 }
                fn count(m: int_map):
                  returns: int
                  return: map_size(m)
                main:
                  print: count(make())
                """);
        assertTrue(ll.contains("define %ow.intmap* @make()"));
        assertTrue(ll.contains("define i32 @count(%ow.intmap* %m.arg)"));
        assertTrue(ll.contains("call %ow.intmap* @make()"));
        assertTrue(ll.contains("call void @__ow_native_intmap_release(%ow.intmap*"));
    }

    @Test public void stringValuesSelectDistinctStringMapABI() {
        String llvm = ir("""
                main:
                  set m: { a: "hello" }
                  print: m["a"]
                """);
        assertTrue(llvm.contains("call %ow.strmap* @__ow_native_strmap_new()"));
        assertTrue(llvm.contains("call %ow.string @__ow_native_strmap_get("));
    }

    @Test public void integerMapCannotBeIndexedWithIntegerKey() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { a: 1 }
                  print: m[0]
                """));
        assertTrue(error.getMessage().contains("keys must be strings"));
    }

    @Test public void integerMapCannotBeUpdatedWithStringValue() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: {}
                  set m["a"]: "not an int"
                """));
        assertTrue(error.getMessage().contains("requires integer values"));
    }

    @Test public void mapHasChecksArgumentCount() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  print: map_has({}, "a", "extra")
                """));
        assertTrue(error.getMessage().contains("expects 2 argument"));
    }

    @Test public void unsupportedMapKeysBuiltinFailsExplicitly() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { a: 1 }
                  set ks: map_keys(m)
                """));
        assertTrue(error.getMessage().contains("not yet supported"));
    }

    @Test public void rejectsWrongTypeWhenPassingNativeIntMap() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                fn size(m: int_map):
                  returns: int
                  return: map_size(m)
                main:
                  print: size([1, 2])
                """));
        assertTrue(error.getMessage().contains("expects %ow.intmap*"));
    }

    @Test public void emptyMapAllocatesAndHasZeroEntries() {
        String ll = ir("""
                main:
                  set m: {}
                  print: map_size(m)
                """);
        assertTrue(ll.contains("call %ow.intmap* @__ow_native_intmap_new()"));
        assertTrue(ll.contains("define i32 @__ow_native_intmap_size("));
    }
}
