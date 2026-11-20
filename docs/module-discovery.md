# Native OW runtime module discovery

OW's interpreter exposes `module_scan("MODULE_TYPE")` to discover **loaded,
publicly reachable native module values** whose signatures structurally satisfy
a declared `module_type`. This supports frameworks such as Night without
manually listing every compatible module in application code.

```ow
module_type SERVICE:
  fn name() -> string

module Example: SERVICE
  fn name() -> string:
    return: "example"

main:
  set services: module_scan("SERVICE")
  call: assert(len(services) == 1)
  print: services[0].name()
```

## Discovery contract

- The runtime discovers values from the current program's loaded/imported
  declarations, qualified namespaces and main-scope functor instances.
- It includes only **publicly reachable** module paths; private lexical
  module children cannot be enumerated by the scan.
- Filtering uses OW's existing structural signature checks, not a naming
  convention or reflection on internal Java classes.
- An alias referencing the same underlying OW module appears once. Results
  have deterministic name-based traversal order, not dependency order.
- Unknown signature names, invalid arguments or wrong arity fail explicitly.
- The scan returns module values, never arbitrary files, Java objects, classes,
  functors themselves, or private implementation methods. Instantiation
  of functors still requires the developer to supply typed module arguments.
- Only modules already imported/loaded into the program are considered. This
  feature does **not** search project directories or implicitly import every
  source file. A separate compile-time dependency resolution/autoload feature
  would be required for that behavior.
- This interpreter-only capability is **not yet lowered to LLVM/native output**.
  Native compilation must reject unsupported dynamic reflection rather than
  silently dropping the operation.

Night's `NightApp.discover_native()` builds on this API and retains the
existing explicit `use_native_as` method for opt-in overrides and backwards
compatibility.
