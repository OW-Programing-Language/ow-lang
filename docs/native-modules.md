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

Native functors are **applicative** by default: applying the same functor to
the same stable argument module path gives its abstract result types the same
identity. Explicit `functor_gen` creates fresh result type identities for
each application, even with the same argument. See
[functor_identity.ow](../examples/module_calculus/functor_identity.ow).

**Manifest type sharing:** `module_type INT_BOX:` can declare
`type token: int` rather than `type token`. The underlying type becomes
part of the public contract and is checked against each module's implementation;
compatible modules share the concrete type instead of wrapping it opaquely.
See [manifest_sharing.ow](../examples/module_calculus/manifest_sharing.ow).

The implementation is an **incremental stage**, not a full OCaml module calculus.
The following are not implemented and remain acceptance criteria in #16:
full abstract-type polymorphism, type-sharing constraints, path-dependent type
identities, higher-order functors, the full applicative/generative static type calculus,
fully static analysis of functor bodies, compilation to native binaries,
fine-grained source-span diagnostics, and compiler-enforced import boundaries.

Existing OW files without module declarations keep their prior semantics.

## Path-dependent functor result types

Functor result signatures can now share abstract types with their module
parameter, using an explicit `with_type` equation:

```ow
functor Forward(S: BOX) -> BOX with_type token = S.token:
  type token: S.token
  fn wrap(value int) -> token:
    return: S.wrap(value)
  fn unwrap(value token) -> int:
    return: S.unwrap(value)
```

This is different from giving the result a fresh opaque type: a token issued
by `S` can be passed directly to the result, and a token returned by the result
can be used by `S`. The module checker validates the equality path and the
implementation declaration before `main`; public calls enforce it at runtime.
The alias does *not* grant access to `S`'s private representation.
See [dependent_result.ow](../examples/module_calculus/dependent_result.ow).

This implements **first-order dependent result sharing**, not the full OCaml
`with type` constraint language or static path-dependent type inference.

## Higher-order functors

A `functor_type` declares a public signature for a functor, including its input
and output module signatures:

```ow
functor_type MAKER(SOURCE) -> RESULT

functor Decorate(F: MAKER) -> RESULT:
  fn result() -> int:
    set created: F(Source)
    return: created.result() + 1

functor Identity(F: MAKER) -> MAKER:
  return_functor: F
```

Functors are now first-class values: a functor can accept a compatible functor
as an argument, invoke it inside a module export, and return a compatible
functor that can be applied again. The checker rejects incompatible return
signatures before `main`; applications validate functor signatures as well.
See [higher_order.ow](../examples/module_calculus/higher_order.ow).

This is first-class higher-order composition. It is not complete OCaml
polymorphic higher-order module inference, and the runtime still evaluates
functor applications rather than compiling module types away.

## Static checking of known module values

In addition to declaration checking, OW now performs a conservative semantic
pass over `main` before any user statements execute. The pass tracks module
values produced by named functor applications, abstract token origins, manifest
type members and known primitive argument values.

Examples rejected before execution:

- passing `First.token` to a method requiring `Second.token`
- mixing two `functor_gen` result tokens from separate applications
- assigning an abstract token to `int`
- using an opaque token in arithmetic
- passing a known `int` literal to a module method that requires `string`

Repeated applicative applications of the same functor to the same stable
module argument share the same abstract type in this preflight checker.
Explicit `with_type token = Arg.token` keeps the parameter's abstract type
identity in the result. Errors include source line numbers where available.

The analysis is deliberately conservative: dynamically computed values and
interactions outside statically known module paths still require runtime
checks. It does not claim full OCaml type inference or whole-program flow
analysis.

## Named-module with_type constraints

A named module may share an abstract type identity with another module:

```ow
module Alias: BOX with_type token = First.token:
  type token: First.token
  fn wrap(x int) -> token:
    return: First.wrap(x)
  fn unwrap(x token) -> int:
    return: First.unwrap(x)
```

This preserves the identity of First.token without disclosing its representation.
The compiler rejects invalid module paths, missing type members, incompatible
constraints and alias cycles. Structural width signature matching still requires
all public method names, parameter types, and return types to agree.
See the NamedTypeSharingTest suite and named_type_sharing.ow example.

## OCaml Set.Make comparison and source diagnostics

The [Set.Make comparison](../examples/module_calculus/set_make.ow) provides a
small immutable collection with `empty`, `add`, `member`, and `size`.
The `MakeSet(Order: ORDERED)` functor requires an abstract comparison
interface and produces a `SET` whose element type is tied to `Order.t`
with `with_type`. The implementation uses OW arrays and performs duplicate
elimination; unlike OCaml's standard balanced-tree Set, it is a small
linear-time educational analogue.

The module checker emits location-aware diagnostics for incompatible module
applications, missing exports, parameter/return mismatches, and certain
abstract type escapes. Import errors, forbidden infra references and import
cycles include the original file and import line/column. Lexer tokens carry
1-based source spans with exclusive end columns. Ordinary dynamic expressions
still require interpreter checks and are not all statically typed.

## Compile-time semantic validation

Run `ow check path/to/file.ow` to parse imports and validate native module
declarations, functor applications, known module values, and abstract
representation escapes **without executing `main`**. Errors include file,
line and column when known. This command is suitable for CI preflight checks.

`ow build` also invokes the module semantic checker, and now **refuses**
first-class module/functor code with an explicit unsupported LLVM-backend error.
The procedural LLVM subset cannot yet lower OW module values; refusing to
silently emit an incomplete executable is intentional. Use `ow run` for module
execution. Full native-code module compilation remains a separate task.

## Include module signatures

A `module_type` may now `include` one or more other signatures:

```ow
module_type LIFECYCLE:
  fn name() -> string
  fn start() -> int

module_type INVENTORY:
  include LIFECYCLE
  type sku
  fn issue(value int) -> sku
```

The compiler expands inherited public methods and type members, validates
implementations against the complete signature, rejects unknown includes,
cycles, incompatible method overrides and conflicting manifest types.
Imports can supply included signatures from other files.
This is **signature inclusion only**; implementation-level `include`,
nested module paths and module-alias syntax remain future OW work.

## Native module aliases and nested paths

OW now supports transparent aliases: `module Inventory = Warehouse` and
qualified aliases such as `module Shop.Inventory = Warehouse`. Aliases preserve
the original module value, signature and opaque type identity. `Shop.Inventory`
is a namespace path accepted in method calls, typed bindings and functor input
expressions. The checker rejects unknown alias targets, cycles and namespace
collisions. See [module_aliases.ow](../examples/module_calculus/module_aliases.ow).

**Limit:** these are *nested alias paths*, not full OCaml nested module
implementations, module functors as submodules, or arbitrary first-class module
packing/unpacking. Those require further syntax/type-system work.

## Qualified native module definitions

You can now define real native modules under dotted namespace paths instead
of only aliasing an existing top-level module:

```ow
module_type Shop.API:
  fn get() -> int
module Shop.Inventory: Shop.API
  fn get() -> int:
    return: 15
module Inventory = Shop.Inventory
```

The type checker recognizes `Shop.API` in functor inputs/outputs and signature
`include`, and tracks the distinct abstract identities of qualified modules.
A qualified definition creates a namespace object at runtime, allowing direct
`Shop.Inventory.get()` calls. Alias paths retain the same original identity.
This is **qualified module declarations**, not full OCaml nested lexical
module structures (with recursive submodules, local sealing and nested functors).

## First-class module packages

`pack(module, "SIGNATURE")` creates an existential-style, sealed
module package, which can be stored in arrays and records or passed as
a dynamic value. `unpack(package, "SIGNATURE")` recovers a typed module
view, preserving the original opaque identity and disallowing widening
to exports hidden by the package's signature.

```ow
set packed: pack(Store, "READ")
set items: [packed]
set view READ: unpack(items[0], "READ")
print: view.get()
```

Known pack/unpack operations with literal signatures are validated by
`ow check`; dynamically retrieved packages are checked during evaluation.
Unlike full OCaml `(module M : S)` existential semantics, module packages
are not yet accompanied by universal static type inference across
heterogeneous containers or LLVM native lowering.

## Include module implementations

`include Warehouse` inside a `module` or `functor` body now imports
the source's **public signature exports** as delegates to the original
module, rather than duplicating its code or private representation.

```ow
module Inventory: STOCK
  include Warehouse
  fn available(x token) -> int:
    return: 1
```

The checker detects cycles, unknown included modules, conflicting inherited
exports and accidental abstract-type representation redefinition. Local
methods can override inherited implementations, and a functor may
`include` its typed module parameter.

Abstract types inherited via implementation `include` share the **source**
module's identity through a type path (`Warehouse.token`); this is
intentional to avoid inventing an unrelated generative identity.

This supports a statically resolvable module path or functor argument.
It does not yet reproduce OCaml's full `include struct ... end`,
arbitrary local structures, recursive module equations or compiler-managed
first-class module inference.

## LLVM AOT for statically resolved integer module/functor calls

The `ow llvm input.ow output.ll` command now lowers the **static i32
subset** of native modules and first-order functors to ordinary LLVM
functions. `ow build` uses the same lowering before invoking the system
toolchain. A functor application with a known module argument is specialized
at compile time; qualified module paths, aliases and implementation-level
`include` work where the exposed methods use `int` arguments and returns.

```sh
ow llvm examples/module_calculus/native_llvm.ow /tmp/native.ll
clang /tmp/native.ll -o /tmp/native
/tmp/native   # prints 14
```

This is **not general native compilation of OCaml modules**.
It intentionally rejects abstract type representations, arbitrary
first-class packages, dynamic module dispatch, higher-order functor
values and non-`int` module export types. Runtime module semantics remain
available with `ow run`. The IR can be tested without installing Zig or a
Windows SDK using the `llvm` command.

## Lexically nested named module implementations

Native module bodies may now contain nested `module_type` and `module`
declarations. A parent `module_type` explicitly declares visible child
modules, so private children are accessible to the owner's methods but
cannot be selected or aliased from outside the owner.

```ow
module_type ITEM:
  fn value() -> int
module_type SHOP:
  module Inventory: ITEM
  fn total() -> int

module Store: SHOP
  module Inventory: ITEM
    fn value() -> int:
      return: 12
  fn total() -> int:
    return: Inventory.value()
```

Nested signatures resolve within their enclosing lexical scope. Nested
implementations are normalized to qualified names for semantic checking,
but the interpreter attaches them to their owning module with signature
sealing rather than creating unrelated public namespace aliases.

This adds lexical nested **named modules and signatures** with explicit
public/private visibility. It does not yet support mutually recursive
module bindings, nested functor declarations/anonymous structs, local
`include struct ... end`, or the complete OCaml module grammar.

## Differential OCaml conformance suite

`tools/ocaml_conformance.py` builds and runs paired OCaml and OW source files
from `tests/ocaml_conformance/`. Positive tests compare exact numeric output
for lexical module nesting, implementation `include`, first-class module
packages, dependent functor result sharing and higher-order functors. The
static LLVM module fixture is compared both through the OW interpreter and
through an executable compiled with `clang`. Negative tests require *both*
OCaml's compiler and `ow check` to reject incompatible signatures, unrelated
opaque module identities, and hidden nested modules.

```sh
sudo apt-get install ocaml-nox clang
mvn -B package
python3 tools/ocaml_conformance.py
```

This is a **representative behavior-comparison suite**, not exhaustive
conformance to every OCaml module calculus rule or its entire standard library.
