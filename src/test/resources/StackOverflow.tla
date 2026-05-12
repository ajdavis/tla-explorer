---- MODULE StackOverflow ----
EXTENDS Integers
VARIABLE x
RECURSIVE f(_)
f(n) == f(n + 1)
Init == x = 0
\* f recurses infinitely; triggers StackOverflowError during Next evaluation.
Next == x' = f(x)
====
