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

    @Test public void opaqueModuleRequiresALoweringStrategy() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () -> ir("""
                module_type API:
                  type token
                  fn make(v int) -> token
                module M: API
                  type token: int
                  fn make(v int) -> token:
                    return: v
                main:
                  set x: M.make(3)
                """));
        assertTrue(ex.getMessage().contains("abstract type lowering"));
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
