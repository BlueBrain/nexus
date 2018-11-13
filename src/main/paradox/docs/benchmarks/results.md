# Results

TBC.

## Create Simulation

## Create Simulation (no validation)

## Fetch Simulation

## Mixed Simulation

## Tag Simulation

## GetByTag Simulation

# Conclusions

The "_Create Simulation_" and "_Create Simulation (no validation)_" shows the impact of SHACL validation for resources
on the throughput an latency. Most of the CPU cycles (~ 90%) are spent running the validation.

The system scales fairly well with the number of nodes allocated, but depends on each of the operations. Although the
number of concurrent requests is generally higher with more nodes, the penalty of node to node communication can have
a fairly big impact. For example: assembling schemas (following `owl:import` and `@context` references) implies a lot of
cross node communication; for "_Create Simulation_" increasing the cluster size from 1 to 2, while it shows an increase
in the total throughput, the value is not double to that of a single node.
