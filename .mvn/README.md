# Why this directory exists

This directory has no wrapper or config in it — it exists solely so the
`mvn` launcher can find it while walking up from wherever it's invoked, and
pin `${maven.multiModuleProjectDirectory}` to the true repo root.

Without it, running `mvn` from inside a submodule (e.g. `cd logaperture-it
&& mvn install`, instead of `mvn -pl logaperture-it ...` from the root)
makes that property resolve to the submodule directory instead, which
breaks any pom that builds a path from it —
[logaperture-it/pom.xml](../logaperture-it/pom.xml) locates the prebuilt
agent/CLI jars this way.

Git doesn't track empty directories, hence this file.
