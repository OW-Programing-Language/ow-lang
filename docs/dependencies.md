# OW project dependencies: dependencies.yml

OW projects can manage reusable OW source libraries using a YAML manifest at
the **project root**, instead of copying the same library into every project.

This feature is opt-in for existing programs: projects without a manifest continue
to use the normal local \`import "file.ow"\` syntax. New projects that need external
libraries can run \`ow deps init\`.

## Manifest

Create \`dependencies.yml\` beside your project root:

\`\`\`yaml
format: 1

repositories:
  - id: github
    url: https://github.com
  - id: company
    url: https://git.example.com

dependencies:
  - name: math
    repository: github
    path: OW-Programing-Language/ow-math-questions
    version: c22d01cfee6be18d96d4c55b95b7e79892bca9f6
\`\`\`

- \`repositories\` lists HTTPS Git base URLs (no usernames/passwords embedded).
- \`dependencies\` lists the imported library aliases, repository names,
  repository-relative paths, and version references.
- \`version\` accepts a Git tag, branch, or full 40-character commit SHA.
  **Prefer an immutable commit SHA or a release tag**; updates are pinned by the lockfile.
- \`name\` becomes the import alias (for example, \`math\`).
- There are no implicit repositories: each repository must be declared explicitly.

## CLI

\`\`\`sh
ow deps init                # Generate dependencies.yml in the current directory
ow deps install             # Resolve/download dependencies; use existing lock commits
ow deps list                # Show declared external libraries
ow deps update              # Refresh all refs and recreate the lockfile

ow run src/main.ow          # Auto-resolve dependencies if a manifest exists
ow check src/main.ow        # Same resolution before type checking
ow build src/main.ow        # Same resolution before native compilation
ow package src/main.ow     # Bundle resolved library sources with the application
\`\`\`

Optional directory arguments are supported: \`ow deps install ./my-project\`.
The nearest \`dependencies.yml\` is discovered by walking upward from the OW
entry file's directory, so \`src/main.ow\` sees the project manifest.

## Importing libraries

\`\`\`ow
import "@math/src/MathQuestions.ow"

main:
  set generator: new MathQuestions()
  set question: generator.generate_any(["+", "-", "*", "/"], 1, 10)
  print: question.prompt
\`\`\`

The \`@math/\` prefix refers to the declared dependency, **not** the directory
of the importing OW source file. Traditional local imports remain unchanged:

\`\`\`ow
import "../shared/Utils.ow"
\`\`\`

## Cache and reproducibility

- The resolver downloads Git repositories into \`.ow/deps/<name>/\`.
- \`dependencies.lock.yml\` records the exact checked-out Git commit for each
  dependency and should be **committed to version control**.
- \`ow deps install\` reuses locally cached locked commits without connecting
  to the network.
- If the manifest's version/path changes, \`ow deps update\` is required so the
  lockfile cannot silently drift.
- \`ow deps update\` refreshes refs and updates the lockfile. Review lockfile
  diffs before committing.
- Bundled OW applications include their resolved OW files and lock metadata,
  not the dependencies' \`.git\` directories; this lets them run offline.

Add this to the consuming project's \`.gitignore\`:

\`\`\`gitignore
.ow/deps/
\`\`\`

Do not commit the cache. Commit the manifest and lockfile.

## Limitations (first iteration)

- **OW source libraries only:** This is not a Maven JAR resolver. The project's
  own Java runtime still uses Maven when building the OW interpreter.
- **Direct dependencies only:** Transitive metadata resolution, semantic-version
  ranges, package registries, conflict resolution, and checksum-verified binary
  artifacts are future work.
- **Git required to install/update** dependencies; already cached bundled
  applications run without Git.
- Loading a dependency runs that dependency's OW source in the application.
  Use libraries from sources you trust.
- Native LLVM compilation remains limited to the subset the compiler supports;
  resolving a library does not make unsupported OW syntax natively compilable.

See [the example project](../examples/dependencies-demo/).
