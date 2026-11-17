# YAML-style OW function signatures

The preferred OW syntax uses **`name: type`** for parameters and
**`returns: type`** as the first declaration inside a function/method body.
You do not need to type the ASCII arrow `->`.

```ow
fn greet(name: string):
  returns: string
  return: "Hello " + name

fn sum(a: int, b: int):
  returns: int
  return: a + b

fn createUsers(count: int):
  returns: array
  return: array(count, 0)
```

`returns:` declares the type a function is expected to return; `return:`
is the executable statement that returns an actual value. The `returns:`
declaration is parsed into the function's type metadata and **is not an OW
statement**. It must appear first in the function body, if present.

## Compatibility

All pre-existing OW syntax remains valid:

```ow
fn greet(name string) -> string:
  return: "Hello " + name

fn sum(a int, b int) -> int:
  return: a + b

fn original(a, b):
  return: a + b
```

You may use colon-typed and legacy parameters in the same declaration. A
function must not declare its return type with both `-> type` in its header
and `returns: type` in its body; this is a parse error.

## Classes, modules, interfaces

Methods in classes, modules and functors also support the preferred syntax:

```ow
class Calculator:
  fn add(a: int, b: int):
    returns: int
    return: a + b
```

Interface and `module_type` signatures (which have no executable body)
support an indented `returns:` declaration as well:

```ow
module_type CALCULATOR:
  fn add(a: int, b: int):
    returns: int
```

Old single-line interface/module-type signatures with `-> type` still parse
normally. Untyped signatures do not require either form of return annotation.

## Compiling

The new and old forms produce the same AST parameter-type and return-type
metadata, so the existing interpreter and native LLVM compiler receive
equivalent signatures. The native backend still supports only a subset of
OW types; YAML-style syntax does not extend the native type system by itself.

```sh
ow run examples/yaml_signatures.ow
ow llvm examples/yaml_signatures.ow target/yaml-signatures.ll
clang target/yaml-signatures.ll -o target/yaml-signatures
./target/yaml-signatures
```
