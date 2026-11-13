package dev.oreo;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class NativeModuleLlvmLowererTest {
    private String ir(String source) {
        Ast.Program program = new Parser(new Lexer(source, "llvm-static.ow")
                .lexAll()).parseProgram();
        ModuleTypeChecker.check(program);
        Ast.Program lowered = NativeModuleLlvmLowerer.lower(program);
        assertTrue(lowered.modules.isEmpty());
        assertTrue(lowered.functors.isEmpty());
        return new LlvmIrGen().generate(lowered, "llvm_module_test");
    }

    @Test public void staticallyNamedModuleEmitsLLVMFunctions() {
        String generated = ir("""
                module_type API:
                  fn plus(a int, b int) -> int
                module Math: API
                  fn plus(a int, b int) -> int:
                    return: a + b
                main:
                  print: Math.plus(3, 4)
                """);
        assertTrue(generated.contains("define i32 @__ow_module_"));
        assertTrue(generated.contains("call i32 @__ow_module_"));
        assertTrue(generated.contains(" add i32 "));
    }

    @Test public void staticallyAppliedFunctorSpecializesCapturedModule() {
        String generated = ir("""
                module_type API:
                  fn inc(x int) -> int
                module M: API
                  fn inc(x int) -> int:
                    return: x + 1
                module_type OUT:
                  fn calc(x int) -> int
                functor Apply(Source: API) -> OUT:
                  fn calc(x int) -> int:
                    return: Source.inc(x) * 2
                main:
                  set service OUT: Apply(M)
                  print: service.calc(4)
                """);
        assertTrue(generated.contains(" mul i32 "));
        assertTrue(generated.split("define i32 @__ow_module_").length >= 3);
    }

    @Test public void qualifiedAliasAndIncludedImplementationLower() {
        String generated = ir("""
                module_type API:
                  fn get() -> int
                module Shop.Source: API
                  fn get() -> int:
                    return: 10
                module Alias = Shop.Source
                module Included: API
                  include Alias
                main:
                  print: Included.get()
                """);
        assertTrue(generated.split("define i32 @__ow_module_").length >= 3);
    }

    @Test public void staticallyPackedModuleCanBeUnpackedAsNarrowInterface() {
        String generated = ir("""
                module_type READ:
                  fn get() -> int
                module_type EXTENDED:
                  include READ
                  fn secret() -> int
                module Store: EXTENDED
                  fn get() -> int:
                    return: 42
                  fn secret() -> int:
                    return: 99
                main:
                  set sealed: pack(Store, "READ")
                  set reader READ: unpack(sealed, "READ")
                  print: reader.get()
                """);
        assertTrue(generated.contains("call i32 @__ow_module_"));
    }

    @Test public void directStaticPackUnpackCanSpecializeOpaqueIntegerToken() {
        String generated = ir("""
                module_type API:
                  type token
                  fn wrap(x int) -> token
                  fn unwrap(x token) -> int
                module M: API
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                  fn unwrap(x token) -> int:
                    return: x
                main:
                  set checked API: unpack(pack(M, "API"), "API")
                  print: checked.unwrap(checked.wrap(8))
                """);
        assertTrue(generated.contains("define i32 @__ow_module_"));
    }

    @Test public void sealedPackageCannotWidenAtNativeCompilation() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                module_type READ:
                  fn get() -> int
                module_type EXTENDED:
                  include READ
                  fn secret() -> int
                module Store: EXTENDED
                  fn get() -> int:
                    return: 1
                  fn secret() -> int:
                    return: 99
                main:
                  set sealed: pack(Store, "READ")
                  set wrong: unpack(sealed, "EXTENDED")
                """));
        assertTrue(error.getMessage().contains("cannot widen"));
    }

    @Test public void runtimeArrayOfPackagesIsStillExplicitlyUnsupported() {
        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class, () -> ir("""
                module_type READ:
                  fn get() -> int
                module Store: READ
                  fn get() -> int:
                    return: 1
                main:
                  set shelf: [pack(Store, "READ")]
                  print: 0
                """));
        assertTrue(error.getMessage().contains("only i32/static module expressions"));
    }

    @Test public void firstClassModuleCannotBeSilentlyErased() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () -> ir("""
                module_type API:
                  fn get() -> int
                module M: API
                  fn get() -> int:
                    return: 5
                main:
                  set packed: pack(M, "API")
                  print: 0
                """));
        assertTrue(ex.getMessage().contains("first-class module package values"));
    }

    @Test public void opaqueIntegerModuleCanBeLowered() {
        String llvm = ir("""
                module_type API:
                  type token
                  fn make(v int) -> token
                module M: API
                  type token: int
                  fn make(v int) -> token:
                    return: v
                main:
                  set x: M.make(3)
                """);
        assertTrue(llvm.contains("define i32 @__ow_module_"));
    }

    @Test public void opaqueIntegerTypeIsErasedOnlyAfterStaticValidation() {
        String generated = ir("""
                module_type BOX:
                  type token
                  fn wrap(value int) -> token
                  fn unwrap(value token) -> int
                module First: BOX
                  type token: int
                  fn wrap(value int) -> token:
                    return: value + 3
                  fn unwrap(value token) -> int:
                    return: value
                main:
                  set t: First.wrap(8)
                  print: First.unwrap(t)
                """);
        assertTrue(generated.contains("define i32 @__ow_module_"));
        assertTrue(generated.contains("call i32 @__ow_module_"));
    }

    @Test public void dependentAbstractTypeCanLowerToItsCapturedIntegerRepresentation() {
        String generated = ir("""
                module_type BOX:
                  type token
                  fn wrap(value int) -> token
                  fn unwrap(value token) -> int
                module First: BOX
                  type token: int
                  fn wrap(value int) -> token:
                    return: value + 3
                  fn unwrap(value token) -> int:
                    return: value
                functor Forward(Source: BOX) -> BOX with_type token = Source.token:
                  type token: Source.token
                  fn wrap(value int) -> token:
                    return: Source.wrap(value)
                  fn unwrap(value token) -> int:
                    return: Source.unwrap(value)
                main:
                  set service BOX: Forward(First)
                  print: service.unwrap(service.wrap(10))
                """);
        assertTrue(generated.contains("define i32 @__ow_module_"));
        assertTrue(generated.contains("call i32 @__ow_module_"));
    }

    @Test public void opaqueTokenFromDifferentModuleFailsBeforeLowering() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> ir("""
                module_type BOX:
                  type token
                  fn wrap(value int) -> token
                  fn unwrap(value token) -> int
                module A: BOX
                  type token: int
                  fn wrap(value int) -> token:
                    return: value
                  fn unwrap(value token) -> int:
                    return: value
                module B: BOX
                  type token: int
                  fn wrap(value int) -> token:
                    return: value
                  fn unwrap(value token) -> int:
                    return: value
                main:
                  set t: A.wrap(2)
                  print: B.unwrap(t)
                """));
        assertTrue(failure.getMessage().contains("abstract type mismatch"));
    }

    @Test public void opaqueStringRepresentationIsNotMiscompiledAsInteger() {
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class, () -> ir("""
                module_type BOX:
                  type token
                  fn wrap(value string) -> token
                  fn unwrap(value token) -> string
                module S: BOX
                  type token: string
                  fn wrap(value string) -> token:
                    return: value
                  fn unwrap(value token) -> string:
                    return: value
                main:
                  set t: S.wrap("hello")
                  print: S.unwrap(t)
                """));
        assertTrue(failure.getMessage().contains("only i32-representable"));
    }

    @Test public void cliEmitsLLVMWithoutExecutingProgram() throws Exception {
        Path source = Files.createTempFile("ow-llvm-modules-", ".ow");
        Path out = Files.createTempFile("ow-llvm-output-", ".ll");
        Files.writeString(source, """
                module_type API:
                  fn get() -> int
                module M: API
                  fn get() -> int:
                    return: 42
                main:
                  print: M.get()
                """);
        Main.main(new String[]{"llvm", source.toString(), out.toString()});
        String llvm = Files.readString(out);
        assertTrue(llvm.contains("call i32 @__ow_module_"));
    }
}
