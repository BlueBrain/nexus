# Run Nexus locally with Docker

## Requirements

### Docker

Regardless of your OS, make sure to run a recent version of Docker (community edition).
This was tested with versions **18.03.1** and above.
You might need to get installation packages directly
from the [official Docker website](https://docs.docker.com/) if the one provided by your system
package manager is outdated.

Command
:  
```
docker --version
```

Example
:  
```
$ docker version
Docker version 18.09.1, build 4c52b90
```

### Memory and CPU limits

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor.
Nexus requires at least **2 CPUs** and **8 GiB** of memory in total. You can increase the limits
in Docker settings in the menu *Preferences* > *Advanced*.

## Option 1: Docker Compose

While not recommended, we provide a [Docker Compose template](./docker-compose/docker-compose.yaml)
in the old *version 2* format. Download the file into a directory of your choice, for instance `~/docker/nexus/`.
You can then simply run `docker-compose up` within this directory:

Command
:  
```
docker-compose up
```

Example
:  
```
$ docker-compose up
Creating network "nexus_default" with the default driver
Pulling cassandra (cassandra:3)...
[...]
Creating nexus_admin_1         ... done
Creating nexus_blazegraph_1    ... done
Creating nexus_router_1        ... done
Creating nexus_iam_1           ... done
Creating nexus_web_1           ... done
Creating nexus_cassandra_1     ... done
Creating nexus_kg_1            ... done
Creating nexus_elasticsearch_1 ... done
Attaching to nexus_iam_1, nexus_kg_1, nexus_blazegraph_1, nexus_cassandra_1, nexus_elasticsearch_1, nexus_admin_1, nexus_router_1, nexus_web_1
[...]
```

## Option 2: Docker Swarm

Docker Swarm is the native orchestrator shipped with Docker. It provides a more robust way to run Nexus.

If you've never used Docker Swarm or Docker Stacks before, you first need to create a swarm cluster
on your local machine:

Command
:  
```
docker swarm init
```

Example
:  
```
$ docker swarm init
Swarm initialized: current node (***) is now a manager.
 
To add a worker to this swarm, run the following command:
 
    docker swarm join --token {token} 128.178.97.243:2377
 
To add a manager to this swarm, run 'docker swarm join-token manager' and follow the instructions.
```

### Deployment

Download the [Docker Compose template](./docker-swarm/docker-compose.yaml) into a directory of your choice.
For instance `~/docker/nexus/`.

### Starting Nexus

Create a *nexus* deployment with Docker Stacks:

Command
:  
```
docker stack deploy nexus --compose-file=docker-compose.yaml
```

Example
:  
```
$ cd ~/docker/nexus
$ docker stack deploy nexus --compose-file=docker-compose.yaml
Creating network nexus_default
Creating service nexus_iam
Creating service nexus_admin
Creating service nexus_elasticsearch
Creating service nexus_cassandra
Creating service nexus_blazegraph
Creating service nexus_router
Creating service nexus_kg
```

Wait one or two minutes and you should be able to access Nexus locally, on the port 80:

Command
:  
```
curl http://localhost/kg
```

Example
:  
```
$ curl http://localhost/kg
{"name":"kg","version":"1.0.0"}
```

### Administration

To list running services or access logs, please refer to the official Docker
[documentation](https://docs.docker.com/engine/reference/commandline/stack/).

Alternatively you can deploy [Swarmpit](https://swarmpit.io/) which provides a comprehensive UI
to manage your Docker Swarm cluster.

### Stopping Nexus

You can stop and delete the entire deployment with:

Command
:  
```
docker stack rm nexus
```

Example
:  
```
$ docker stack rm nexus
Removing service nexus_admin
Removing service nexus_blazegraph
Removing service nexus_cassandra
Removing service nexus_elasticsearch
Removing service nexus_iam
Removing service nexus_kg
Removing service nexus_router
Removing network nexus_default
```

@@@ note

As no data is persisted outside the containers, **everyting will be lost** once you remove the Nexus
deployment. If you'd like help with creating persistent volumes, feel free to contact us on our
[Gitter channel](https://gitter.im/BlueBrain/nexus).

@@@

## Endpoints

The provided reverse proxy (the `nexus-router` image) exposes several endpoints:

* [root](http://localhost): Nexus web interface
* [v1](http://localhost/v1): API root
* [admin](http://localhost/admin): Admin service descriptor
* [iam](http://localhost/iam): IAM service descriptor
* [kg](http://localhost/kg): KG service descriptor
* [elasticsearch](http://localhost/elasticsearch): Elasticsearch endpoint
* [blazegraph](http://localhost/blazegraph): Blazegraph web interface

If you'd like to customize the listening port or remove unnecessary endpoints, you can build your own
Nginx based Docker image. See the [reference configuration](./docker-swarm/nginx.conf).
