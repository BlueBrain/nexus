# Run Nexus locally with Minikube

[Minikube](https://github.com/kubernetes/minikube) is a tool that makes it easy to run Kubernetes locally. Minikube runs
a single-node Kubernetes cluster inside a VM on your machine for users looking to try out Kubernetes or develop with it
day-to-day.

@@@ note

This section makes use of static assets hosted on this website; to remove the clutter please export the base of the
documentation to `$MINI` env var:

```
export MINI="https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/minikube"
```

@@@

@@@ note

This page presents the necessary commands to deploy Nexus with Minikube but also examples the show the expected output.

Some of the examples on this page make use of [curl](https://curl.haxx.se/) and [jq](https://stedolan.github.io/jq/)
for formatting the json output when interacting with the services. Please install these command line tools if you'd like
to run the commands in the examples. They should be available through your usual package manager (Chocolatey, Homebrew,
APT, YUM/DNF, ...)

@@@

## Minikube set-up

### Installation

Follow the [installation instructions](https://kubernetes.io/docs/tasks/tools/install-minikube/) from the official Kubernetes
documentation.

### Run Minikube

To start Minikube run (notice the _cpu_ and _memory_ flags, the setup requires a minimum of `--cpus=2 --memory=8196`):

```
minikube start --cpus 6 --memory 10240 --vm-driver=$DRIVER
```

For better performance we recommended to select the `$DRIVER` corresponding to your OS native hypervisor, namely
_hyperkit_ on macOS, _hyperv_ on Windows and _kvm2_ on Linux.

If the installation is successful you can run the following command to open the
[Kubernetes Dashboard](http://kubernetes.io/docs/user-guide/ui/):

```
minikube dashboard
```

To stop Minikube run:

```
minikube stop
```

@@@ note

After stopping minikube the vm still exists on the system; starting minikube again will preserve the deployed services.
To permanently remove minikube vm run:

```
minikube delete
```

@@@

### Enable the ingress addon

Minikube comes with a collection of addons like the [Kubernetes Dashboard](http://kubernetes.io/docs/user-guide/ui/) but
not all are enabled by default. An important one is the _ingress_ addon which enables routing http traffic from the
host into the cluster.

To make sure the _ingress_ addon is enabled run:

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
minikube ip
```

Example:
:   
```
$ minikube ip
192.168.64.3
$
```

### Setup a separate namespace

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

Nexus uses numerous off the shelf services that need to be set up as a prerequisite. Run the following command to
save the IP address of the minikube cluster in an environment variable:

Command
:   
```
export NEXUS=$(minikube ip)
```

Example
:   
```
$ export NEXUS=$(minikube ip)
$ echo $NEXUS
192.168.64.3
$
```

### Deploy Cassandra

Command
:   
```
kubectl apply -f $MINI/cassandra.yaml && \
  kubectl wait pod cassandra-0 --for condition=ready --timeout=180s
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

### Deploy Elasticsearch

Command
:   
```
kubectl apply -f $MINI/elasticsearch.yaml && \
  kubectl wait pod elasticsearch-0 --for condition=ready --timeout=180s
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
  "name" : "0LfjOb2",
  "cluster_name" : "es-cluster",
  "cluster_uuid" : "ZZF9_hFgTm2wQYYBKQ9dRg",
  "version" : {
    "number" : "6.4.3",
    "build_flavor" : "default",
    "build_type" : "tar",
    "build_hash" : "fe40335",
    "build_date" : "2018-10-30T23:17:19.084789Z",
    "build_snapshot" : false,
    "lucene_version" : "7.4.0",
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
kubectl apply -f $MINI/blazegraph.yaml && \
  kubectl wait pod blazegraph-0 --for condition=ready --timeout=180s
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

IAM is the service that manages identities and tokens via OIDC providers and manages the permissions to
arbitrary resources in the system. By default, anonymous users will be granted enough permissions to
fully manage resources in the system. If you'd like to change this behavior, you must set up
[realms and permissions](../../api/iam/index.html) manually.

Command
:   
```
kubectl apply -f $MINI/iam.yaml && \
  kubectl wait pod iam-0 --for condition=ready --timeout=180s
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
  "version": "1.0.1"
}
$ curl -s "http://$NEXUS/v1/acls/" | jq
{
  "@context": [
    "https://bluebrain.github.io/nexus/contexts/resource.json",
    "https://bluebrain.github.io/nexus/contexts/iam.json",
    "https://bluebrain.github.io/nexus/contexts/search.json"
  ],
  "_total": 1,
  "_results": [
    {
      "@id": "http://192.168.39.171/v1/acls",
      "@type": "AccessControlList",
      "acl": [
        {
          "identity": {
            "@id": "http://192.168.39.171/v1/anonymous",
            "@type": "Anonymous"
          },
          "permissions": [
            "schemas/write",
            "views/write",
            "files/write",
            "permissions/write",
            "acls/write",
            "realms/write",
            "projects/read",
            "acls/read",
            "organizations/create",
            "organizations/write",
            "resources/write",
            "realms/read",
            "projects/create",
            "permissions/read",
            "resources/read",
            "organizations/read",
            "resolvers/write",
            "events/read",
            "views/query",
            "projects/write"
          ]
        }
      ],
      "_path": "/",
      "_rev": 0,
      "_createdAt": "1970-01-01T00:00:00Z",
      "_createdBy": "http://192.168.39.171/v1/anonymous",
      "_updatedAt": "1970-01-01T00:00:00Z",
      "_updatedBy": "http://192.168.39.171/v1/anonymous"
    }
  ]
}
$
```

### Deploy Admin

Admin is the service that manages organizations, projects and their configuration.

Command
:   
```
kubectl apply -f $MINI/admin.yaml && \
  kubectl wait pod admin-0 --for condition=ready --timeout=180s
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
  "version": "1.0.2"
}
$ curl -s "http://$NEXUS/v1/projects" | jq
{
  "@context": [
    "https://bluebrain.github.io/nexus/contexts/admin.json",
    "https://bluebrain.github.io/nexus/contexts/resource.json",
    "https://bluebrain.github.io/nexus/contexts/search.json"
  ],
  "_total": 0,
  "_results": []
}
$ 
```

### Deploy KG

KG is the service that manages user defined resources, their schemas and configuration like resolvers, views etc.

Command
:   
```
kubectl apply -f $MINI/kg.yaml && \
  kubectl wait pod kg-0 --for condition=ready --timeout=180s
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
  "version": "1.0.0"
}
$ curl -s "http://$NEXUS/v1/resources/org/proj" | jq # the 404 error is expected
{
  "@context": "https://bluebrain.github.io/nexus/contexts/error.json",
  "@type": "ProjectNotFound",
  "label": "org/proj",
  "reason": "Project 'org/proj' not found."
}
$
```

### Deploy the web interface

The Nexus web application provides an interface to perform basic tasks on organizations and projects and query the
system resources through Elasticsearch and SPARQL queries.

Command
:   
```
kubectl apply -f $MINI/web.yaml && \
  kubectl wait pod nexus-web-0 --for condition=ready --timeout=180s
```

Example
:   
```
$ kubectl apply -f $MINI/web.yaml
service/nexus-web created
statefulset.apps/nexus-web created
ingress.extensions/nexus-web created
$ kubectl wait pod nexus-web-0 --for condition=ready --timeout=180s
pod/nexus-web-0 condition met
$
```

You can now access the web interface at [http://$NEXUS](http://$NEXUS), `$NEXUS` being the public IP of your Minikube
cluster, as seen above.
