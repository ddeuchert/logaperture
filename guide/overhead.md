# Overhead

!!! note "Being written"
    The measured cost of the agent per log call, idle and with a `trim` rule, `top` counting and
    storm detection, will be published here with 1.0. The measurement work is tracked in
    [#128](https://github.com/ddeuchert/logaperture/issues/128).

In short, for now: with nothing configured the agent adds a small cost to every log call, and
storm detection is off by default because it was the largest single part of that cost.
