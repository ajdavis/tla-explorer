---- MODULE Counter ----
EXTENDS Integers

(*--algorithm Counter
variables x = 0;
begin
  MainLoop:
    while x < 3 do
      Inc: x := x + 1;
    end while;
end algorithm;*)
\* BEGIN TRANSLATION (chksum(pcal) = "e217d20c" /\ chksum(tla) = "5521c624")
VARIABLES pc, x

vars == << pc, x >>

Init == (* Global variables *)
        /\ x = 0
        /\ pc = "MainLoop"

MainLoop == /\ pc = "MainLoop"
            /\ IF x < 3
                  THEN /\ pc' = "Inc"
                  ELSE /\ pc' = "Done"
            /\ x' = x

Inc == /\ pc = "Inc"
       /\ x' = x + 1
       /\ pc' = "MainLoop"

(* Allow infinite stuttering to prevent deadlock on termination. *)
Terminating == pc = "Done" /\ UNCHANGED vars

Next == MainLoop \/ Inc
           \/ Terminating

Spec == Init /\ [][Next]_vars

Termination == <>(pc = "Done")

\* END TRANSLATION 

====
