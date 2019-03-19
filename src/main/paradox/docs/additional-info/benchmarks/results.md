# Results

## Create Simulation

| Nodes | Users | Throughput (req/s) | p50 (ms) | p75 (ms) | p95 (ms) | p99 (ms) |
|-------|-------|--------------------|----------|----------|----------|----------|
|     1 |    16 |                 36 |      513 |      681 |      820 |      999 |
|     2 |    32 |                 51 |      572 |      706 |     1107 |     1191 |
|     4 |    64 |                106 |      586 |      722 |     1033 |     1423 |
|     6 |    96 |                148 |      589 |      802 |     1209 |     1741 |

## Create Simulation (no validation)

| Nodes | Users | Throughput (req/s) | p50 (ms) | p75 (ms) | p95 (ms) | p99 (ms) |
|-------|-------|--------------------|----------|----------|----------|----------|
|     1 |    16 |                456 |       11 |       72 |       85 |      198 |
|     2 |    32 |                490 |       33 |       74 |      239 |      403 |
|     4 |    64 |               1063 |       26 |       77 |      180 |      366 |
|     6 |    96 |                891 |       59 |      113 |      431 |      546 |

## Fetch Simulation

| Nodes | Users | Throughput (req/s) | p50 (ms) | p75 (ms) | p95 (ms) | p99 (ms) |
|-------|-------|--------------------|----------|----------|----------|----------|
|     1 |    16 |               1849 |        4 |        4 |       55 |       68 |
|     2 |    32 |               2826 |        5 |        6 |       47 |       93 |
|     4 |    64 |               3440 |       10 |       13 |       53 |      116 |
|     6 |    96 |               3860 |       15 |       21 |       57 |      127 |

## Mixed Simulation

| Nodes | Users | Throughput (req/s) | p50 (ms) | p75 (ms) | p95 (ms) | p99 (ms) |
|-------|-------|--------------------|----------|----------|----------|----------|
|     1 |    16 |                467 |       14 |       29 |       80 |      269 |
|     2 |    32 |                566 |       15 |       25 |       89 |     1076 |
|     4 |    64 |                567 |       16 |       27 |      128 |     3552 |
|     6 |    96 |                506 |       16 |       26 |      154 |     7182 |

## Tag Simulation

| Nodes | Users | Throughput (req/s) | p50 (ms) | p75 (ms) | p95 (ms) | p99 (ms) |
|-------|-------|--------------------|----------|----------|----------|----------|
|     1 |    16 |                508 |        7 |       69 |       95 |      195 |
|     2 |    32 |                699 |       16 |       76 |      100 |      199 |
|     4 |    64 |               1107 |       15 |       80 |      163 |      276 |
|     6 |    96 |               1661 |       26 |       72 |      144 |      257 |

## GetByTag Simulation

| Nodes | Users | Throughput (req/s) | p50 (ms) | p75 (ms) | p95 (ms) | p99 (ms) |
|-------|-------|--------------------|----------|----------|----------|----------|
|     1 |    16 |                156 |       86 |      116 |      298 |      418 |
|     2 |    32 |                189 |      118 |      215 |      406 |      586 |
|     4 |    64 |                192 |      200 |      460 |      975 |     1381 |
|     6 |    96 |                285 |      202 |      477 |      988 |     1409 |

# Conclusions

The "_Create Simulation_" and "_Create Simulation (no validation)_" shows the impact of SHACL validation for resources
on the throughput an latency. Most of the CPU cycles (~ 90%) are spent running the validation.

The system scales fairly well with the number of nodes allocated, but depends on each of the operations. Although the
number of concurrent requests is generally higher with more nodes, the penalty of node to node communication can have
a fairly big impact. For example: assembling schemas (following `owl:import` and `@context` references) implies a lot of
cross node communication; for "_Create Simulation_" increasing the cluster size from 1 to 2, while it shows an increase
in the total throughput, the value is not double to that of a single node.
