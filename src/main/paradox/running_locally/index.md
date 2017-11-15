# Running locally 

The easiest way to run BlueBrain Nexus locally is to use `docker-compose`.

## Prerequisites 

In order to run Nexus on your local machine make sure that you have Docker and Docker Compose installed. Docker compose comes bundled together with docker which you can download from [Docker website](https://www.docker.com/).

## Running using docker-compose  

Copy or download [docker-compose.yaml](../assets/running_locally/docker-compose.yaml) file.

@@snip [docker-compose.yaml](../assets/running_locally/docker-compose.yaml) 

and then run `docker-compose up` in the folder where `docker-compose.yaml` file is located.

If you're running it for the first time it will take a few moments to download the required docker images.

After all the containers have started you should be able to verify which version of Nexus KG and Nexus IAM are running by going to `http://localhost:8080/` and `http://localhost:8081/` respectively.

All the paths for KG start with `http://localhost:8080/v0`, while all the IAM paths start with `http://localhost:8081/v0.`

When everything is up and running run 
```
curl -XPUT -H "Content-Type: application/json" http://127.0.0.1:8081/v0/acls/kg/ -d '{"acl":[{"permissions":["read","write","own","publish"],"identity":{"type":"Anonymous"}}]}'
``` 
to make sure that Anonymous users have permissions for all the operations in KG.

Please visit @extref[Nexus KG documentation](service:kg) to for more details.