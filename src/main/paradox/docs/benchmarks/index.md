@@@ index

* [Deployment Configuration](deployment-configuration.md)
* [Scenarios](scenarios.md)
* [Results](results.md)

@@@

# Benchmarks

The main goal of the benchmarks was to analyze the hardware requirements for a Nexus deployment and to find potential bottlenecks.

The benchmarks were run on a Kubernetes cluster deployed on AWS. Fore more details see [deployment configuration](deployment-configuration.md).
The tests were run against v1 API of Nexus in October 2018.

The test scenarios and scripts can be found in [nexus-tests](https://github.com/BlueBrain/nexus-tests) repository.
The description of the scenarios can be found [here](scenarios.md)

The benchmarks were using `final_number_of_instances` million of instances distributed in `final_number_of_projects` of projects.