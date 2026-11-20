# OW-native HTTP mappings and transactional methods

The OW interpreter supports method annotations `@GetMapping("/path")`,
`@PostMapping("/path")` and `@Transactional` for OW classes.

```ow
@Service
class OrderService:
  @Transactional
  fn create() -> int:
    call: extension("night.data.execute", "INSERT INTO orders(name) VALUES (?)", ["item"])
    return: 1

@Controller
class OrderController:
  @Inject
  field private orders OrderService

  @PostMapping("/api/orders")
  fn create() -> int:
    return: this.orders.create()

main:
  # Night opens the JDBC pool before annotated transactions begin.
  # Use NightApp.mount_controllers() after NightApp.boot(), or:
  call: component_mount(component_get("OrderController"))
  serve: 8080
```

## HTTP mapping rules

- Mapping declarations are allowed only on methods of imported `@Controller`
  OW classes. A mapped method is public and zero-argument in this first stage.
  Request data remains accessible through existing OW builtins `body()`,
  `body_json()`, `param("id")` and `query("key")` while the handler runs.
- Mappings register with the same OW HTTP runtime as explicit `route`
  blocks. The normal authentication middleware, bounded request body, JSON
  responses, status codes and duplicate-route rejection apply.
- `component_mount(controller)` registers mapped methods. Controllers
  without method mappings retain their manual `mount()` method behavior.
  Mixing both styles on one controller fails. Night's `mount_controllers()`
  will use this builtin.
- Duplicate annotated paths/methods fail before `main:`. Static, trusted
  route paths are required; arbitrary query fragments aren't supported.
- There is **no** automatic request parameter injection or file scanning.

## Transaction boundaries

`@Transactional` wraps an OW method with Night JDBC `begin()`,
`commit()` on successful completion and `rollback()` on runtime
exceptions/errors. Transaction execution requires an installed Night platform
extension and a successfully opened Night JDBC pool.

Nested annotated calls use **REQUIRED** semantics: they join the outer
transaction instead of opening a second JDBC transaction. If an inner
annotated method throws, the outer transaction is marked rollback-only even
if application code catches the exception. At outer exit the runtime refuses
to commit and rolls the transaction back.

Supported: single JDBC connection per OW execution thread; synchronous
method calls; rollback of database writes executed through Night JDBC.
Not supported: multi-resource/XA transactions, distributed messaging atomicity,
`REQUIRES_NEW`, transaction savepoints, automatic retry, reactive request
threads, asynchronous propagation or arbitrary non-database side-effect
reversal. OW's LLVM native compiler does not yet lower this dynamic feature.

Use prepared statements and do not put credentials into OW source.
