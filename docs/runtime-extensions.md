# OW Java Runtime Extension SPI

OW applications can install optional Java runtime providers without making OW's
compiler depend on a particular framework. This capability is intentionally
minimal: providers implement `dev.oreo.RuntimeExtension` and advertise
`META-INF/services/dev.oreo.RuntimeExtension` in their runtime JAR.

A registered extension receives two entry points:

- `extension("provider.operation", arg1, ...)` dispatches explicit operations
  from OW code. If no provider supports the name (or multiple providers do),
  OW fails closed with a runtime error.
- `beforeRequest(HttpExchange)` runs before any OW route handler, and may
  return an HTTP response to deny the request. `afterRequest` observes the
  final HTTP status and duration even when a request is rejected or fails.

Example, with the optional Night platform JAR on the classpath:

```sh
java -cp 'OwIDE.jar:night-platform.jar' dev.oreo.Main run app.ow
```

```ow
main:
  call: extension("night.security.enable")
  route "GET /":
    respond: "protected"
  serve: 8080
```

Provider instances are scoped to an Interpreter. A provider must be thread
safe if an application uses parallel requests in future OW runtimes.
Production providers should never put secrets into HTTP error bodies.
OW's built-in web runtime currently supports a single request worker and
a 1 MiB request body limit; it should run behind an appropriately configured
reverse proxy for TLS, connection limits, and slow-client protection.

This SPI is not an ORM or an authentication system itself; it supports
installable framework modules without changing the OW language grammar.
