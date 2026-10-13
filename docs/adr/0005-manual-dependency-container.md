# ADR-0005: Manual dependency container, no DI framework
Status: Accepted (v0.1)

`AppContainer` constructs the small object graph explicitly. A DI framework would add a dependency (ADR-0001) and code generation without improving clarity at this size. Revisit if the graph exceeds ~40 bindings.
