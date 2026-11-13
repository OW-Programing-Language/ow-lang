# Native OW modules: first-stage module calculus

OW supports new top-level declarations:

```ow
module_type ORDERED:
  fn compare(a int, b int) -> int

module Ascending: ORDERED
  fn compare(a int, b int) -> int:
    if: a < b
      return: 0 - 1
    if: a > b
      return: 1
    return: 0

module_type CHOOSER:
  fn choose(a int, b int) -> int

functor MakeChooser(Order: ORDERED) -> CHOOSER:
  fn choose(a int, b int) -> int:
    if: Order.compare(a, b) < 1
      return: a
    return: b

main:
  set selected CHOOSER: MakeChooser(Ascending)
  print: selected.choose(3, 8)
```

The compiler front end recognizes `module_type`, `module`, and `functor`
declarations as AST nodes. A semantic checker validates both module and functor
exports before running the entry `main` block. It also analyzes calls through
statically known module values and functor parameters: a mismatched application,
unknown public method, or incorrect arity causes a module type error with the
source line before any `main` statement executes. Module types match
*structurally*: a module exposing more members can satisfy a narrower functor
parameter, provided each required method has the same parameter and return
types. Functor applications accept module values only, not arbitrary objects.

Module values expose only the methods listed in their signature, preventing
ordinary callers from accessing private implementation helpers. Parameter and
return values are validated at method call boundaries.

Multi-file imports are supported. See
[examples/module_calculus](../examples/module_calculus/README.md) and test using
`mvn test` plus `ow run examples/module_calculus/main.ow`.

## Limitations and issue #16

OW additionally supports opaque abstract type members, declared in signatures as
`type token` and implemented as `type token: int`. At public method
boundaries, OW wraps raw representation values into unforgeable abstract module
values. A `token` from one named module cannot be passed to another module,
even when both represent tokens internally as integers. See
[abstract_types.ow](../examples/module_calculus/abstract_types.ow).

The implementation is an **incremental stage**, not a full OCaml module calculus.
The following are not implemented and remain acceptance criteria in #16:
full abstract-type polymorphism, type-sharing constraints, path-dependent type
identities, higher-order functors, applicative/generative abstract types,
fully static analysis of functor bodies, compilation to native binaries,
fine-grained source-span diagnostics, and compiler-enforced import boundaries.

Existing OW files without module declarations keep their prior semantics.
