# Native OW module types and functor application

```ow
module_type ORDERED:
  fn compare(a int, b int) -> int

module_type CHOOSER:
  fn choose(a int, b int) -> int

functor MakeChooser(Order: ORDERED) -> CHOOSER:
  fn choose(a int, b int) -> int:
    if: Order.compare(a, b) < 1
      return: a
    return: b
```

Run: `ow run examples/module_calculus/main.ow`.

Separate files declare signatures, three ORDERED implementations, and a
functor. Module values can be passed to functors by name. The functor's parameter
is checked against the input module type **structurally** (including width
subtyping), and the returned module is constrained by the declared output
signature. Methods not declared in that signature are inaccessible to callers.

The parser uses native `module_type`, `module`, and `functor` declarations
rather than treating them as runtime `class` factories. The interpreter runs a
semantic signature check before user `main` statements.

This is a meaningful native **first stage** of issue #16, not full OCaml parity.
Abstract type members, opaque type equality, sharing constraints, generativity,
applicative module identity, higher-order functors, and rich source-span
diagnostics remain future work.

## Advanced OCaml-inspired module feature integration

[advanced_module_calculus.ow](advanced_module_calculus.ow) is a **standalone
OW language** sample (it does not import flOW). It composes:

- Lexically nested `module` and `module_type`, with public signature sealing;
- Implementation-level `include` and transparent module re-exports;
- A typed functor consuming a module with an exported nested module;
- First-class `pack`/`unpack` values stored inside arrays and records.

Run `ow check examples/module_calculus/advanced_module_calculus.ow` and
`ow run examples/module_calculus/advanced_module_calculus.ow`.

For AOT LLVM output from the **static integer subset**, use the separate
[native_llvm.ow](native_llvm.ow) fixture. General dynamic packages and
abstract module type values do not yet lower to native code.
