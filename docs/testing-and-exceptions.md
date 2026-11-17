# OW test annotations and exceptions

OW supports `@Test`, `@BeforeEach`, and `@AfterEach` on **zero-argument**
top-level functions and class methods. Tests run using the interpreter:

```sh
ow test tests/CalculatorTest.ow
ow test tests/
ow test tests/CalculatorTest.ow --filter CalculatorTest.addsNumbers
```

For directory discovery, test files must end with `Test.ow` or `_test.ow`.
Test files need not contain `main:`, and `ow test` never executes the main
block even if one exists. Class-method tests use one new object per test, so
classes must have a zero-argument constructor. Setup and teardown methods run
around every test. Results list individual pass/fail status and the process
exits nonzero when any test fails or no tests are found. Use `--filter`
to execute just one top-level function or one `ClassName.methodName`. An
unknown test filter returns a failing status rather than silently succeeding.

```ow
class CalculatorTest:
  field value

  @BeforeEach
  fn setup():
    set this.value: 4

  @Test
  fn addsNumbers():
    call: assert(this.value + 3 == 7, "wrong sum")

  @AfterEach
  fn cleanup():
    print: "done"
```

The OWUnit library supplies more expressive assertions:
[OWUnit](https://github.com/OW-Programing-Language/OWUnit).

## Exception handling

```ow
main:
  try:
    throw: "something went wrong"
  catch message:
    print: "Caught " + message
  finally:
    print: "Cleanup executes"
```

`try` requires at least one `catch error:` or `finally:` clause.
`catch` binds the error message as a string. `finally` executes on normal
completion, caught and uncaught exceptions, and `return:`. `throw:` raises
an exception with the supplied value's string representation.
`assert(condition[, message])` raises a catchable exception on failure,
rather than terminating the JVM directly. Uncaught assertion failures still
cause a nonzero CLI exit.

Previously supported `try/catch` code continues to work.

## Limitations

- Test annotations currently recognize only `@Test`, `@BeforeEach`,
  `@AfterEach`; no arbitrary decorators, arguments or parameterized tests.
- Hooks are per scope (one before/after per class or top-level file).
- Only the interpreter implements annotations and structured exceptions.
  Native LLVM/EXE compilation is not yet supported for these features.
