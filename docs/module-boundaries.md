# Native API / implementation import boundaries

Module layouts with an `api/` or `infra/` directory are automatically checked
by the OW import loader.

```text
shop/
  main.ow                     # composition root; can import implementations
  inventory/
    api/InventoryApi.ow       # public exports
    infra/Inventory.ow        # private implementation
  orders/
    api/OrdersApi.ow
    infra/Orders.ow
```

Code inside `orders/infra/` can import `inventory/api/`, but **cannot** import
`inventory/infra/`. Code in `inventory/api/` cannot import any `infra/`
implementation (including its own). Code inside `inventory/infra/` may import
its own implementation files. The application composition root, which is
outside these folders, may wire all implementation modules together.

The enforcement is part of `Loader`, not an optional linting script. Illegal
imports fail before evaluating `main:`, with the source path and import line.
Import cycles are also rejected explicitly; shared DAG imports still work.

This opt-in-by-folder convention preserves compatibility for existing OW
programs that do not use `api/` and `infra/` layouts. It does not yet prove
module isolation for arbitrary computed values, reflection or dynamic imports,
and it does not provide a full OCaml compile-time module checker.
