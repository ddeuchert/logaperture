# Use recipes

A **recipe** is a ready-made set of logging changes for watching one thing, written by someone who
knows which loggers matter: a library's authors, the product's vendor, or your own team. You find
one, read what it changes, switch it on for a while, and switch it off again.

The output on this page is from WildFly 26.1.3, with one recipe in the recipes folder.

## Find one

```sh
logctl list recipes
```

```
ID                    SUMMARY                                      SOURCE                 APPLIED
io.undertow:requests  Log each HTTP request as Undertow routes it  recipes/undertow.yaml  -
```

Recipes come from three places, shown in `SOURCE`:

- **Libraries** the application uses, which ship a recipe file in their jar. A library's recipes
  are listed only once the application has loaded it.
- **The vendor defaults file**, if the product has one.
- **The recipes folder**: any `*.yaml` file in `~/.logaperture/recipes`, or the folder named by
  `-Dlogaperture.recipes`. A file you drop there is listed at once, with no restart.

`--verbose` shows each source's full path, recipes hidden by another with the same id, and files
that couldn't be read.

## Read what it changes

```sh
logctl show recipe io.undertow:requests
```

```
io.undertow:requests — Log each HTTP request as Undertow routes it
Source: /opt/jboss/wildfly/standalone/tmp/logaperture/recipes/undertow.yaml

  Turns on Undertow request routing at DEBUG. One line per request;
  noisy under load, so use it for minutes, not hours.

Changes:
  logger io.undertow.request           INFO -> DEBUG  request routing
  logger io.undertow.request.security  INFO -> DEBUG
```

Each change shows the level now and the level the recipe sets. Nothing changes yet.

## Switch it on

```sh
logctl apply recipe io.undertow:requests for 30m
```

`apply` shows the same changes and asks before making them; `--yes` skips the question, and is
needed in a script. Without a tier, the changes last `for 4h`.

```
logger  io.undertow.request           → DEBUG
logger  io.undertow.request.security  → DEBUG
Applied recipe io.undertow:requests (2 changes, FOR, reverts 01:11:13 local — in 29m).
```

The changes are ordinary overrides and rules that remember which recipe made them. They show in
`status` like any other, with the reason from the recipe:

```
LOGGER                        LEVEL  TIER  REVERTS            REASON
io.undertow.request           DEBUG  FOR   01:11:13 (in 29m)  "request routing"
io.undertow.request.security  DEBUG  FOR   01:11:13 (in 29m)  "recipe io.undertow:requests"
```

and `list recipes` shows the recipe as applied:

```
ID                    SUMMARY                                      SOURCE                 APPLIED
io.undertow:requests  Log each HTTP request as Undertow routes it  recipes/undertow.yaml  for, 29m left (2 of 2)
```

Applying a recipe again sets its changes again, with the new tier; rules it adds get new ids.

## Switch it off

The changes revert by themselves when their tier ends. To undo them sooner:

```sh
logctl reset recipe io.undertow:requests
```

```
logger io.undertow.request reset.
logger io.undertow.request.security reset.
Reset recipe io.undertow:requests (2 changes).
```

`reset recipe` only undoes changes that still belong to the recipe. If you changed one of its
loggers by hand since (`set logger`, `alter rule`), that change is yours now and stays. Like other
resets, it skips `sticky` changes unless you add `--include-sticky`.

A recipe's changes stay after its library is undeployed. `list recipes` then still shows the
recipe, marked as no longer offered, so you can reset it.

## Write your own

A recipe file in the recipes folder can set logger levels, handler levels and `drop` and `trim`
rules. Its format is in [File formats](../reference/file-formats.md#recipe-files). To ship one with
a library, see [Recipes for library authors](../vendors/library-recipes.md).
