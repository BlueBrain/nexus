@@@ index

* [Deployment Configuration](deployment-configuration.md)
* [Data Volume and Scenarios](data-volume-and-scenarios.md)
* [Results](results.md)

@@@

# Benchmarks

The main goal of the benchmarks was to analyze the hardware requirements for a Nexus deployment and to find potential bottlenecks.
In particular, we were most interested in the following metrics:

* **throughput** - how many requests per second the system can handle
* **latency** - the time the system needed to provide response to the requests

and how they were affected by different factors, especially:

* **data volume** - how does the volume of the data in the system affect the performance
* **hardware configuration and scalability** - does assigning more hardware increase the performance of the system and can the system scale both horizontally and vertically.
* **clustering** - what's the effect of changing from a single node to clustered deployment, as well as, what's the effect of adding more nodes to the cluster.

The description of the test scenarios can be found @ref:[here](data-volume-and-scenarios.md).
The test scenarios and scripts can be found in [nexus-tests](https://github.com/BlueBrain/nexus-tests) repository.
The results of the benchmarks are described in detail in the @ref:[Results section](results.md).

The benchmarks were run on a Kubernetes cluster deployed on AWS. Fore more details see @ref:[deployment configuration](deployment-configuration.md).
The tests were run against v1 API of Nexus in November 2018 using [Gatling](https://gatling.io/) version 3.0.0.