---- MODULE OrderNormalization ----
EXTENDS Integers

VARIABLES vset_asc, vset_desc, vrcd_xy, vrcd_yx, vfcn_24, vfcn_42

Init ==
    /\ vset_asc  = {1, 2, 3}
    /\ vset_desc = {3, 2, 1}
    /\ vrcd_xy   = [x |-> 1, y |-> "world"]
    /\ vrcd_yx   = [y |-> "world", x |-> 1]
    /\ vfcn_24   = [i \in {2, 4} |-> i * 10]
    /\ vfcn_42   = [i \in {4, 2} |-> i * 10]

Next == UNCHANGED <<vset_asc, vset_desc, vrcd_xy, vrcd_yx, vfcn_24, vfcn_42>>
====
