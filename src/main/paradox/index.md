@@@ index

* [Components](components/index.md)
* [Platform API](api/index.md)
* [Running locally](running_locally/index.md)
* [Roadmap](roadmap/index.md)

@@@

# BlueBrain Nexus

The BlueBrain Nexus is a provenance based, semantic enabled data management platform enabling the definition of an
arbitrary domain of application for which there is a need to create and manage entities as well as their relations
(e.g. provenance). For example, the domain of application managed by the Nexus platform deployed at Blue Brain is to
digitally reconstruct and simulate the brain.

At the heart of the BlueBrain Nexus platform lies the Knowledge Graph; at BlueBrain, it will allow scientists to:

1. Register and manage neuroscience relevant entity types through schemas that can reuse or extend community defined
schemas (e.g. schema.org, bioschema.org, W3C-PROV) and ontologies (e.g. brain parcellation schemes, cell types,
taxonomy).

2. Submit data to the platform and describe their provenance using the W3C PROV model. Provenance is about how data or
things are generated (e.g. protocols, methods used...), when (e.g. timeline) and by whom (e.g. people, software...).
Provenance supports the data reliability and quality assessment as well as enables workflow reproducibility. Platform
users can submit data either through web forms or programmatic interfaces.

3. Search, discover, reuse and derive high-quality neuroscience data generated within and outside the platform for the
purpose of driving their own scientific endeavours.
Data can be examined by species, contributing laboratory, methodology, brain region, and data type, thereby allowing
functionality not currently available elsewhere. The data are predominantly organized into atlases (e.g. Allen CCF,
Waxholm) and linked to the KnowledgeSpace – a collaborative community-based encyclopedia linking brain research concepts
to the latest data, models and literature.

It is to be noted that many other scientific fields (Astronomy, Agriculture, Bioinformatics, Pharmaceutical Industry,
...) are in need of such a technology. Consequently, BlueBrain Nexus core technology is being developed to be
**agnostic of the domain** it might be applied to.

# Nexus Components

The Nexus platform is made up of a collection of services and web applications that work together to manage data stored
within the system.  The services and web applications are powered by a collection of libraries and tools built
specifically to address the needs of the platform.  Underneath it all there are popular open source technologies that
we all know and love.

## Services

### Nexus KnowledgeGraph

This service is the heart of the BlueBrain Nexus platform. It allows users to define their domain, populate the
knowledge graph with data, attach files to data.  It also provides semantic search facilities to discover similar and
relevant data in the platform.

[Source Code](https://github.com/BlueBrain/nexus-kg) | [Documentation](./kg)

### Nexus IAM

This service is managing the access to data within the platform.  It makes use of configurable downstream OpenID Connect
compliant identity providers to authenticate clients, and manages the access controls for the entire platform.

[Source Code](https://github.com/BlueBrain/nexus-iam) | [Documentation](./iam)


## Web Applications

### Nexus Search

This web application allows users of the nexus platform to search in the knowledge graph. Beyond searching and
inspecting data stored in the platform, its purpose is to enable the discovery of similar and related data.

[Source Code](https://github.com/BlueBrain/nexus-search-webapp)

### Nexus Navigator

This web component allows users of the platform to navigate graphically the knowledge graph data. A user can select an
entity in the knowledge graph and display it neighboring entities in the form of a graph.

[Source Code](https://github.com/BlueBrain/nexus-navigator-webapp)

### Nexus Registration

This web application will allow users to enter data into the knowledge graph using forms generated automatically from
domain's schemas. It's meant for low scale data integration while large scale would typically be done programmatically
through APIs.

[Source Code](https://github.com/BlueBrain/nexus-registration-webapp)

### Nexus Docs

Generated documentation for the platform (this website).

[Source Code](https://github.com/BlueBrain/nexus) | [Website](./)


## Libraries and Tools

### Nexus Commons

A collection of small (scala) libraries that are incubating before breaking off in their own repositories:

* _common-types_: foundation types used across all libraries and services
* _sourcing_: an event sourcing abstraction with an Akka based implementation and an in memory one for testing purposes
* _commons-service_: shared service functionality
* _commons-http_: a preconfigured http client
* _shacl-validator_: SHACL validator abstraction
* _sparql-client_: a preconfigured SPARQL client

[Source Code](https://github.com/BlueBrain/nexus-commons)

### Nexus SBT

An [sbt](http://www.scala-sbt.org/) plugin used by all Scala based projects containing reasonable defaults for Nexus.

[Source Code](https://github.com/BlueBrain/sbt-nexus)

### Nexus Webapp Commons

A collection of small (js) libraries shared by nexus web applications that are incubating before breaking off in their
own repositories.

[Source Code](https://github.com/BlueBrain/nexus-webapp-commons)
