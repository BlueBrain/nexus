# Deployment Configuration

The tests were performed against Nexus services deployed in Kubernetes cluster provisioned by [Amazon EKS](https://aws.amazon.com/eks/).
The deployment configuration and the number of nodes assigned to each Nexus service are presented in the following diagram:

![deployment configuration](https://github.com/BlueBrain/nexus/blob/master/src/main/paradox/assets/img/performance_tests_environment.png)

The benchmarks were run on a AWS EC2 `m5.large` server outside of the Kubernetes cluster.

Preliminary tests shows that KG service is the most critical component of the system (as expected) and it has the most
impact of the performance aspects of the system. Thus, during the tests the KG service cluster size was scaled to 1, 2,
4 and 6 replicas along with the number of concurrent connections (using the same multiplier) during the test executions.
