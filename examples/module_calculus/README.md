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
