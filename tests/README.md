# Nexus integration tests

Starts the delta ecosystem with docker-compose and run tests on it

Relies on sbt-docker-compose: 
https://github.com/Tapad/sbt-docker-compose

To run the all the tests:
```sbtshell
dockerComposeTest skipBuild
```

To reuse a docker-compose instance:
```
dockerComposeUp skipBuild
```
Which will gives an instance id to run tests:
```sbtshell
dockerComposeTest <instance_id>
```
All tests are designed to be run several times in a row without having to start and stop the docker-compose instance

To run just some tests, we can just provide tags:
```sbtshell
dockerComposeTest <instance_id> -tags:tag1,tag2
```

The available tags are:
* Realms
* Permissions
* Acls
* Orgs
* Projects
* Archives
* Resources
* Views
* CompositeViews
* Events
* Storage
* AppInfo
