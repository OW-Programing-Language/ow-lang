# Registering Ow with GitHub Linguist

GitHub detects and colours languages using
[github-linguist/linguist](https://github.com/github-linguist/linguist).
Adding a new language is a **pull request to that repository** — it is not
something this repo can do on its own, and it is gated by adoption.

## Eligibility (important)

Linguist's maintainers will **not** merge a brand-new language until it is in
real use. Their guidance requires the language to be used in a meaningful
number of **unique `:owner/:repo` repositories** (historically on the order of
a few hundred). A language that only appears in this repo will be declined, so
treat this as "prepare now, submit once Ow has traction".

## What's already prepared here

- `grammars/ow.tmLanguage.json` — a TextMate grammar for `.ow` (scope `source.ow`).
- `linguist/languages-entry.yml` — the `languages.yml` entry to add.
- `examples/*.ow` — real sample files Linguist can use for heuristics/tests.

## Steps when eligible

1. Fork `github-linguist/linguist`.
2. Add the entry from `languages-entry.yml` to `lib/linguist/languages.yml`
   (alphabetical order), choosing a **unique** `language_id`.
3. Vendor the grammar: add `ow.tmLanguage.json` and register it in
   `vendor/README.md` / `grammars.yml` (run `script/add-grammar` with a public
   grammar repo URL — publishing the grammar as its own repo is the usual path).
4. Add sample files under `samples/Ow/` (copy from this repo's `examples/`).
5. Run Linguist's tests (`bundle exec rake test`) and
   `bundle exec script/licensed` as the contributing guide describes.
6. Open the PR, including links showing `.ow` usage across many repositories.

## Stopgap: colour your own repos now

Until Ow is registered, you can influence detection per-repository with a
`.gitattributes` file (already added at the repo root). You cannot define a new
language this way, but you can keep `.ow` files detectable and, if you wish,
temporarily map them to a similar existing grammar for highlighting.
