@@@ index
* @ref:[Benchmarks v1.4.2](v1.4.2.md)
* @ref:[Benchmarks v1.2.1](v1.2.1.md)
@@@

# Benchmarks

The main goal of the benchmarks is to analyze the hardware requirements for a Nexus deployment and to find potential
issues and / or bottlenecks. In particular, we are most interested in the following metrics:

*   **throughput** - how many requests per second the system can handle
*   **latency** - the time the system needed to provide response to the requests

... and how they were affected by different factors, especially:

*   **data volume** - how does the volume of the data in the system affect the performance
*   **hardware configuration and scalability** - does assigning more hardware increase the performance of the system and
    can the system scale both horizontally and vertically.
*   **clustering** - what are the effects of changing from a single node to clustered deployment, as well as, what are the effects of adding more nodes to the cluster.

The test scenarios and scripts can be found in the @link:[nexus-benchmarks](https://github.com/BlueBrain/nexus-benchmarks){ open=new }
repository.

The latest benchmarks were run against Nexus Delta v1.4.2, see @ref:[benchmarks](v1.4.2.md).