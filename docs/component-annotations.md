# OW component annotations and dependency injection (experimental)

OW supports small, **OW-native** class stereotypes `@Service` and
`@Controller`, and typed `@Inject` fields. These annotations do not use
Java/Spring annotations, Java classpath scanning, or Spring JARs.

```ow
@Service
class BookService:
  fn title() -> string:
    return: "OW Handbook"

@Controller
class BookController:
  @Inject
  field private books BookService
  fn title() -> string:
    return: this.books.title()

main:
  set controllers: component_scan("Controller")
  call: assert(len(controllers) == 1)
  print: controllers[0].title()
```

## Runtime API

- `component_scan("Service")` returns annotated service singletons.
- `component_scan("Controller")` returns controller singletons.
- `component_scan("All")` returns all annotated class instances.
- `component_get("BookService")` retrieves the same singleton by exact
  **concrete OW class name**. Unknown/unannotated classes are rejected.

Results are sorted by declaration name. The interpreter creates one instance
of each annotated class **per interpreter**, not per HTTP request. Typed
`@Inject` fields resolve other explicitly annotated concrete classes and
reuse their singleton instances. Missing or cyclic dependencies fail during
program initialization **before executing main**. Annotation placement and
field types are checked; duplicate/conflicting annotations are rejected.

Constructors are currently **zero-argument** and injection happens **after**
`new()` completes. Do not reference injected fields inside constructors.
This is field injection, **not** Spring-style constructor injection, interface-
to-implementation binding, scoped beans, proxying or annotation-driven
HTTP method mapping.

The API works only in the OW interpreter. Native LLVM lowering of dynamic
component discovery/injection is not yet implemented and must not be
silently advertised as supported.

Night's `NightApp.mount_controllers()` builds on these primitives. It calls
the public `mount()` method of each annotated controller, so controller
routes remain OW-authored and existing Night/Security rules still apply.
