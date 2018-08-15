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
Docker version 18.03.1-ce, build 9ee9f40
```

### Memory and CPU limits

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor.
Nexus requires at least **2 CPUs** and **8 GiB** of memory in total. You can increase the limits
in Docker settings in the menu *Preferences* > *Advanced*.

### Initialize Docker Swarm

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

## Deployment

Download the [Docker Compose template](./docker/docker-compose.yaml) and
the [Nginx router configuration](./docker/nginx.conf) into a directory of your choice.
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
Creating config nexus_nginx
Creating service nexus_iam
Creating service nexus_admin
Creating service nexus_elasticsearch
Creating service nexus_cassandra
Creating service nexus_kafka
Creating service nexus_blazegraph
Creating service nexus_router
Creating service nexus_kg
```

Wait about one minute and you should be able to access Nexus locally, on the port 80:

Command
:  
```
curl http://localhost
```

Example
:  
```
$ curl http://localhost
{"name":"kg","version":"0.10.11"}
```

To list running services or access logs, please refer to the
[documentation](https://docs.docker.com/engine/reference/commandline/stack/).

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
Removing service nexus_kafka
Removing service nexus_kg
Removing service nexus_router
Removing config nexus_nginx
Removing network nexus_default
```

@@@ note

As no data is persisted outside the containers, **everyting will be lost** once you remove the Nexus
deployment. If you'd like help with creating persistent volumes, feel free to contact us on our
[Gitter channel](https://gitter.im/BlueBrain/nexus).

@@@