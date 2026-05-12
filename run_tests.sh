#!/bin/sh
# Unified test runner. Run from the project root.
set -e
mvn test
python3 examples/explore.py
python3 examples/conformance.py
python3 examples/regression.py
