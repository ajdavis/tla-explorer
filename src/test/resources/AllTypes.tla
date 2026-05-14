---- MODULE AllTypes ----
EXTENDS Integers
CONSTANTS MV

VARIABLES vi, vs, vb, vrcd, vseq, vset, vmv, vfcn, vintv

Init ==
    /\ vi    = 42
    /\ vs    = "hello"
    /\ vb    = TRUE
    /\ vrcd  = [x |-> 1, y |-> "world"]
    /\ vseq  = <<1, 2, 3>>
    /\ vset  = {1, 2, 3}
    /\ vmv   = MV
    /\ vfcn  = [i \in {2, 4} |-> i * 10]
    /\ vintv = 1..3

Next == UNCHANGED <<vi, vs, vb, vrcd, vseq, vset, vmv, vfcn, vintv>>
====
