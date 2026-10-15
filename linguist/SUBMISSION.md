# Registering Ow with GitHub Linguist

GitHub detects and colours languages using
[github-linguist/linguist](https://github.com/github-linguist/linguist).
Adding a new language is a **pull request to that repository**, and it is gated
by adoption — so the plan is "prepare fully now, submit once Ow has traction".

## Eligibility (read first)

Linguist will **not** merge a brand-new language until it is used across a
meaningful number of **unique `:owner/:repo` repositories** (historically on the
order of a few hundred). A language that only appears in this repo will be
declined. Everything below is staged so the PR is a 20-minute job the day Ow
qualifies.

## What's prepared in this repo

| File | Purpose |
|------|---------|
| `grammars/ow.tmLanguage.json` | TextMate grammar, scope `source.ow` (validated JSON) |
| `grammars/LICENSE` | MIT — grammars must be open-source to be vendored |
| `linguist/languages-entry.yml` | the `languages.yml` entry to add |
| `samples/Ow/*.ow` | sample files (Linguist copies these to `samples/Ow/`) |

The `.ow` extension is **unique**, so no `heuristics.yml` disambiguation rules
are needed — the `languages.yml` entry + grammar are sufficient.

## Step 1 — publish the grammar as its own public repo

Linguist vendors grammars as git submodules from public repos, not from inline
files. Create a small public repo (suggested: `OW-Programing-Language/ow-grammar`)
containing `ow.tmLanguage.json` + its `LICENSE`, then note its URL for step 2.

## Step 2 — open the Linguist PR

```sh
git clone https://github.com/github-linguist/linguist
cd linguist
bundle install

# 1. add the grammar submodule (uses the public grammar repo from step 1)
bundle exec script/add-grammar https://github.com/OW-Programing-Language/ow-grammar

# 2. add the Ow entry from languages-entry.yml into lib/linguist/languages.yml
#    (alphabetical order), WITHOUT language_id

# 3. generate the unique language_id
bundle exec script/update-ids

# 4. add samples
mkdir -p samples/Ow && cp /path/to/ow-lang/samples/Ow/*.ow samples/Ow/

# 5. run the checks
bundle exec rake test
bundle exec script/licensed
```

Then open the PR against `github-linguist/linguist`, including links that show
`.ow` usage across many repositories (this is what reviewers look for).

## Stopgap: colour your own repos now

Until Ow is registered, `.gitattributes` at the repo root keeps `.ow` files
detectable. It cannot define a new language (that is what the PR above is for),
so GitHub will show `.ow` as unknown/other in the meantime.
