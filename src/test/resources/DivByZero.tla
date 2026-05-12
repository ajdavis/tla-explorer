---- MODULE DivByZero ----
EXTENDS Integers
VARIABLE x
Init == x = 5
\* Next always divides by zero.
Next == x' = x \div 0
====
