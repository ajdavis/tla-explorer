---- MODULE Mutex ----
\* Simple two-process mutual exclusion. Used in all three examples to
\* demonstrate trace exploration, conformance monitoring, and regression
\* testing with tla-explorer.
VARIABLES p1, p2   \* each is "idle" | "waiting" | "critical"

Init == p1 = "idle" /\ p2 = "idle"

Request1 == p1 = "idle"    /\ p1' = "waiting"  /\ UNCHANGED p2
Request2 == p2 = "idle"    /\ p2' = "waiting"  /\ UNCHANGED p1
Enter1   == p1 = "waiting" /\ p2 # "critical"  /\ p1' = "critical" /\ UNCHANGED p2
Enter2   == p2 = "waiting" /\ p1 # "critical"  /\ p2' = "critical" /\ UNCHANGED p1
Exit1    == p1 = "critical" /\ p1' = "idle"     /\ UNCHANGED p2
Exit2    == p2 = "critical" /\ p2' = "idle"     /\ UNCHANGED p1

Next == Request1 \/ Request2 \/ Enter1 \/ Enter2 \/ Exit1 \/ Exit2
====
