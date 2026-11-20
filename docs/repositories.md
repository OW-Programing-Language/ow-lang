# OW-native generated repositories (experimental)

`@Repository("table", "id_column")` declares a concrete OW singleton
with generated SQL methods backed by the **Night JDBC runtime extension**.
It can be retrieved with `component_get("UserRepository")`, discovered
with `component_scan("Repository")`, and injected into `@Service`
classes through typed `@Inject` fields.

```ow
@Repository("app_users", "id")
class UserRepository:

@Service
class UserService:
  @Inject
  field private users UserRepository

  @Transactional
  fn add(id int, name string) -> int:
    return: this.users.save({id: id, name: name})

main:
  # NightJdbc must already be open (see NightData.open()).
  set service: component_get("UserService")
  call: service.add(1, "OW")
```

## Generated methods (no handwritten repository implementation)

- `save({id: id, column: value})` inserts one record. **INSERT only**;
  duplicate keys fail rather than silently replacing existing rows.
- `findById(id)` returns a single OW record or numeric **0** if missing.
- `existsById(id)` returns 1/0.
- `deleteById(id)` returns the JDBC rows affected (0/1).
- `findAll()` returns at most **500 rows**, ordered by the ID column.
- `page("name", 20, 0)` returns bounded, ascending pages.
  Prefix the sort column with `-` for DESC, e.g. `page("-name", 20, 0)`.

All SQL identifiers are strict, static ASCII names; record keys are checked
too. Values are always JDBC prepared-statement parameters and must be
non-null SQL scalar values. Record fields still need application-level
allowlists/authorization to prevent **mass assignment**; SQL injection
protection does not imply per-column authorization.

Generated repository methods are implemented in the OW **interpreter**
and delegated to Night's existing SQL extension. They run on the same
request/thread-local JDBC connection as `@Transactional`, so an enclosing
transaction commits or rolls back repository writes atomically.

This is deliberately **not JPA or an ORM**: no lazy-loading,
auto-migration, inferred entity mapping, automatic updates, derived
`findByName`, generated IDs, `REQUIRES_NEW`, multi-DB XA transactions,
or native LLVM lowering yet. Repository classes must be empty (no
handwritten fields, methods or inheritance). Configure the schema with
migrations or SQL before calling repository methods.
