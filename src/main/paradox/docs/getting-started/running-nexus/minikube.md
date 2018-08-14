# Run Nexus locally with Minikube

[Minikube](https://github.com/kubernetes/minikube) is a tool that makes it easy to run Kubernetes locally. Minikube runs
a single-node Kubernetes cluster inside a VM on your laptop for users looking to try out Kubernetes or develop with it
day-to-day.

@@@ note

This section makes use of static assets hosted on this website; to remove the clutter please export the base of the
documentation to `$MINI` env var:
```
export MINI="https://bluebrain.github.io/docs/getting-started/minikube/"
```

@@@

## Install Minikube

Follow the [installation instructions](https://github.com/kubernetes/minikube#installation) posted on the Minikube
project page.

## Minikube install instructions for macOS

An example for installing and running Minikube on macOS using Hyperkit after installing
[Docker for Mac](https://docs.docker.com/docker-for-mac/install/):

```
brew install kubectl
brew cask install minikube
curl -Lo docker-machine-driver-hyperkit https://storage.googleapis.com/minikube/releases/latest/docker-machine-driver-hyperkit \
    && chmod +x docker-machine-driver-hyperkit \
    && sudo cp docker-machine-driver-hyperkit /usr/local/bin/ \
    && rm docker-machine-driver-hyperkit \
    && sudo chown root:wheel /usr/local/bin/docker-machine-driver-hyperkit \
    && sudo chmod u+s /usr/local/bin/docker-machine-driver-hyperkit
```

To start Minikube run (notice the _cpu_ and _memory_ flags, the setup requires a minimum of `--cpus=2 --memory=8196`):
```
minikube start --cpus 6 --memory 10240 --vm-driver=hyperkit
```

If the installation is successful you can run the following command to open the
[Kubernetes Dashboard](http://kubernetes.io/docs/user-guide/ui/):

```
minikube dashboard
```

To stop Minikube run:

```
minikube stop
```

## Enable the ingress addon

Minikube comes with a collection of addons like the [Kubernetes Dashboard](http://kubernetes.io/docs/user-guide/ui/) but
not all are enabled by default. An important one is the _ingress_ addon which enables routing http traffic from the
host into the cluster.

To enable the _ingress_ addon run:

Command
:   
```
minikube addons enable ingress
```

Example
:   
```
$ minikube addons enable ingress
ingress was successfully enabled
$
```

To get the external IP of the cluster (to be used later in accessing services) run:

Command:
:   
```
minikube addons open ingress --url | awk -F / '{print $3}' | awk -F : '{print $1}'
```

Example:
:   
```
$ minikube addons open ingress --url | awk -F / '{print $3}' | awk -F : '{print $1}'
192.168.64.3
$
```

## Setup a separate namespace

Kubernetes namespaces are logical groupings of resources which allow segregating various deployments in "virtual
clusters".

The default installation of Minikube creates three namespaces: _kube-system_, _kube-public_ and _default_. This example
uses a separate namespace to group Nexus specific resources.

Get the list of available namespaces:

Command
:   
```
kubectl get namespaces
```

Example
:   
```
$ kubectl get namespaces
NAME          STATUS    AGE
default       Active    1h
kube-public   Active    1h
kube-system   Active    1h
$
```

Create the _nexus_ namespace:

Command
:   
```
kubectl apply -f $MINI/namespace.yaml
```

Example
:   
```
$ kubectl apply -f $MINI/namespace.yaml
namespace/nexus created
$ kubectl get namespaces
NAME          STATUS    AGE
default       Active    1h
kube-public   Active    1h
kube-system   Active    1h
nexus         Active    1m
$
```

Default the `kubectl` to the _nexus_ namespace:

Command
:   
```
kubectl config set-context minikube --namespace=nexus
```

Example
:   
```
$ kubectl config set-context minikube --namespace=nexus
Context "minikube" modified.
$
```

@@@ note

Every time Minikube is stopped and started again, the context and its configuration is lost. Remember to run the
following commands every time you start minikube:

```
kubectl config use-context minikube && kubectl config set-context minikube --namespace=nexus
``` 

@@@

## Deploy dependent services

Nexus uses numerous off the shelf services that need to be set-up as a prerequisite. Run the following command to
save the IP address of the minikube cluster in an environment variable:

Command
:   
```
export NEXUS=$(minikube addons open ingress --url | awk -F / '{print $3}' | awk -F : '{print $1}')
```

Example
:   
```
$ export NEXUS=$(minikube addons open ingress --url | awk -F / '{print $3}' | awk -F : '{print $1}')
$ echo $NEXUS
192.168.64.3
$
```

### Deploy Cassandra

Command
:   
```
kubectl apply -f $MINI/cassandra.yaml
```

Example
:   
```
$ kubectl apply -f $MINI/cassandra.yaml
service/cassandra created
statefulset.apps/cassandra created
$ kubectl exec -it cassandra-0 -- nodetool status
Datacenter: datacenter1
=======================
Status=Up/Down
|/ State=Normal/Leaving/Joining/Moving
--  Address     Load       Tokens       Owns (effective)  Host ID                               Rack
UN  172.17.0.4  103.71 KiB  256          100.0%            80c0bdfa-1f5e-41aa-8a7e-f0dea7fe7ef0  rack1
$
```

### Deploy ElasticSearch

Command
:   
```
kubectl apply -f $MINI/elasticsearch.yaml
```

Example
:   
```
$ kubectl apply -f $MINI/elasticsearch.yaml
service/elasticsearch created
service/elasticsearch-discovery created
statefulset.apps/elasticsearch created
$ kubectl wait pod elasticsearch-0 --for condition=ready --timeout=60s
pod/elasticsearch-0 condition met
$ curl "http://$NEXUS/elasticsearch"
{
  "name" : "elasticsearch-0",
  "cluster_name" : "nexus-cluster",
  "cluster_uuid" : "pvu_3bdoR0az4_9qIw0wlg",
  "version" : {
    "number" : "6.3.1",
    "build_flavor" : "default",
    "build_type" : "tar",
    "build_hash" : "eb782d0",
    "build_date" : "2018-06-29T21:59:26.107521Z",
    "build_snapshot" : false,
    "lucene_version" : "7.3.1",
    "minimum_wire_compatibility_version" : "5.6.0",
    "minimum_index_compatibility_version" : "5.0.0"
  },
  "tagline" : "You Know, for Search"
}
$
```

### Deploy BlazeGraph

Command
:   
```
kubectl apply -f $MINI/blazegraph.yaml
```

Example
:   
```
$ kubectl apply -f $MINI/blazegraph.yaml
service/blazegraph created
statefulset.apps/blazegraph created
persistentvolumeclaim/storage-blazegraph created
ingress.extensions/blazegraph created
$ kubectl wait pod blazegraph-0 --for condition=ready --timeout=180s
pod/blazegraph-0 condition met
$ curl -s -H"Accept: application/json" "http://$NEXUS/blazegraph/namespace?describe-each-named-graph=false" | head -4
  {
    "head" : {
      "vars" : [ "subject", "predicate", "object", "context" ]
    },
$
```

### Deploy Kafka

Command
:   
```
kubectl apply -f $MINI/zookeeper.yaml && \
    kubectl wait pod zookeeper-0 --for condition=ready --timeout=180s && \
    kubectl apply -f $MINI/kafka.yaml
```

## Deploy Nexus Services

Before configuring the services a configuration map must first be created that keeps track of the "public" ip address
of the minikube cluster. The following command will replace the `{NEXUS}` token in the `config.yaml` file with the
value stored in the `$NEXUS` variable set above.

Command
:   
```
curl -s $MINI/config.yaml | sed "s/{NEXUS}/$NEXUS/g" | kubectl apply -f -
```

Example
:   
```
$ curl -s $MINI/config.yaml | sed "s/{NEXUS}/$NEXUS/g" | kubectl apply -f -
configmap/config created
$ kubectl get configmap/config -o yaml | grep public.ip:
  public.ip: 192.168.64.4
$
```

### Deploy IAM

Command
:   
```
kubectl apply -f $MINI/iam.yaml
```

Example
:   
```
$ kubectl apply -f $MINI/iam.yaml
service/iam created
service/iam-hd created
statefulset.apps/iam created
ingress.extensions/iam created
ingress.extensions/iam-direct created
$ kubectl wait pod iam-0 --for condition=ready --timeout=180s
pod/iam-0 condition met
$ curl -s "http://$NEXUS/iam" | jq
{
  "name": "iam",
  "version": "0.10.21",
  "_links": [
    {
      "rel": "api",
      "href": "http://192.168.64.6/v1/acls"
    }
  ]
}
$ curl -s "http://$NEXUS/v1/acls/" | jq
{
  "@context": "http://192.168.64.6/v1/contexts/nexus/core/iam/v0.1.0",
  "acl": [
    {
      "path": "/",
      "identity": {
        "@id": "http://192.168.64.6/v1/anonymous",
        "@type": "Anonymous"
      },
      "permissions": [
        "read",
        "own"
      ]
    }
  ]
}
$
```

### Deploy Admin

Command
:   
```
kubectl apply -f $MINI/admin.yaml
```

Example
:   
```
$ kubectl apply -f $MINI/admin.yaml
service/admin created
service/admin-hd created
statefulset.apps/admin created
ingress.extensions/admin created
ingress.extensions/admin-direct created
$ kubectl wait pod admin-0 --for condition=ready --timeout=180s
pod/admin-0 condition met
$ curl -s "http://$NEXUS/admin" | jq
{
  "name": "admin",
  "version": "0.2.7"
}
$ curl -s "http://$NEXUS/v1/projects" | jq # the access error is expected
{
  "@context": "http://bluebrain.github.io/nexus/contexts/error.json",
  "code": "UnauthorizedAccess"
}
$ 
```

### Deploy KG

Command
:   
```
kubectl apply -f $MINI/kg.yaml
```

Example
:   
```
$ kubectl apply -f $MINI/kg.yaml
service/kg created
service/kg-hd created
statefulset.apps/kg created
persistentvolumeclaim/storage-kg created
ingress.extensions/kg created
ingress.extensions/kg-direct created
$ kubectl wait pod kg-0 --for condition=ready --timeout=180s
pod/kg-0 condition met
$ curl -s "http://$NEXUS/kg" | jq
{
  "name": "kg",
  "version": "0.10.11"
}
$
```