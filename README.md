[![View our technology page](https://img.shields.io/badge/technology-Nexus-03ABE9.svg)](https://bluebrain.github.io/nexus)
[![Join the chat at https://gitter.im/BlueBrain/nexus](https://badges.gitter.im/BlueBrain/nexus.svg)](https://gitter.im/BlueBrain/nexus?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://bbpcode.epfl.ch/ci/buildStatus/icon?job=nexus.sbt.nexus)](https://bbpcode.epfl.ch/ci/job/nexus.sbt.nexus)
[![GitHub Release](https://img.shields.io/github/release/BlueBrain/nexus.svg)]()

# Blue Brain Nexus - A knowledge graph for data-driven science

The Blue Brain Nexus is a provenance based, semantic enabled data management platform enabling the definition of an arbitrary domain of application for which there is a need to create and manage entities as well as their relations (e.g. provenance). For example, the domain of application managed by the Nexus platform deployed at Blue Brain is to digitally reconstruct and simulate the brain.

At the heart of the Blue Brain Nexus platform lies the Knowledge Graph, at Blue Brain, it will allow scientists to:

1. Register and manage neuroscience relevant entity types through schemas that can reuse or extend community defined schemas (e.g. schema.org, bioschema.org, W3C-PROV) and ontologies (e.g. brain parcellation schemes, cell types, taxonomy).

2. Submit data to the platform and describe their provenance using the W3C PROV model. Provenance is about how data or things are generated (e.g. protocols, methods used...), when (e.g. timeline) and by whom (e.g. people, software...). Provenance supports the data reliability and quality assessment as well as enables workflow reproducibility. Platform users can submit data either through web forms or programmatic interfaces.

3. Search, discover, reuse and derive high-quality neuroscience data generated within and outside the platform for the purpose of driving their own scientific endeavours.
Data can be examined by species, contributing laboratory, methodology, brain region, and data type, thereby allowing functionality not currently available elsewhere. The data are predominantly organized into atlases (e.g. Allen CCF, Waxholm) and linked to the KnowledgeSpace – a collaborative community-based encyclopedia linking brain research concepts to the latest data, models and literature.

It is to be noted that many other scientific fields (Astronomy, Agriculture, Bioinformatics, Pharmaceutical industry, ...) are in need of such a technology. Consequently, Blue Brain Nexus core technology is being developed to be **agnostic of the domain** it might be applied to.

# Getting started
## Running locally
The easiest way to run BlueBrain Nexus locally is to use docker-compose.

### Prerequisites
In order to run Nexus on your local machine make sure that you have Docker and Docker Compose installed. Docker compose comes bundled together with docker which you can download from Docker website.

### Running using docker-compose
Copy or download [docker-compose.yaml](https://bbp-nexus.epfl.ch/staging/docs/assets/running_locally/docker-compose.yaml) file.

and then run `docker-compose up` in the folder where docker-compose.yaml file is located.

If you’re running it for the first time it will take a few moments to download the required docker images.

After all the containers have started you should be able to verify which version of Nexus KG and Nexus IAM are running by going to http://localhost:8080/ and http://localhost:8081/ respectively.

Please visit [Nexus KG documentation](https://bbp-nexus.epfl.ch/staging/docs/) for more details.

# Documentation

The overall documentation of the platform is available [here](https://bbp-nexus.epfl.ch/staging/docs/). In the following section of this document, we will also point to specific components documentation.

## Tutorials

Please check out our tutorials:
 - [Nexus KG Schema Format](https://bbp-nexus.epfl.ch/dev/schema-documentation/documentation/shacl-schemas.html#nexus-kg-schemas)
 - [Jupyter notebook with basic operations using Nexus](https://github.com/BlueBrain/nexus/blob/master/tutorial/basic_operations_nexus_v0.ipynb)

## Technical Introduction

Please take a look at [these slides](https://www.slideshare.net/BogdanRoman1/bluebrain-nexus-technical-introduction-91266871)
for a short technical introduction to Nexus.

# Blue Brain Nexus components

## A broad overview of the components of the platform
Given the current product roadmap, here are the components that make Blue Brain Nexus:

![Blue Brain Nexus' components ](https://raw.githubusercontent.com/BlueBrain/nexus/master/images/nexus-components.png)

Now let's take a look at these components individually.

## Backend services

### Nexus KnowledgeGraph
This service is the heart of the Blue Brain Nexus platform. It allows users to define their domain, populate the knowledge graph with data, attach data files to entities. It also provides semantic search facilities to discover similar and relevant data in the platform.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-kg  
**Documentation**: https://bbp-nexus.epfl.ch/staging/docs/kg/

### Nexus IAM (Identity and Access Managment)
This service is a relay to any OAUTH2 identity service the platform maintainers decide to configure authentication against. The purpose of this service is to provide a unified identity service for the nexus platform irrespective of which actual underlying service is being used. Furthermore, this service is managing the privileges granted on entities to users.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-iam  
**Documentation**: https://bbp-nexus.epfl.ch/staging/docs/iam/

### Nexus Admin
This service manages the main resource groupings used throughout the platform. 

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-admin  
**Documentation**: coming soon

### Nexus Commons
This library is meant to contain reusable utilities of the Nexus platform.
Amongst other things, this library contains commons-types, commons-http, shacl-validator, sparql-client, elastic-client.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-commons  

### Nexus Service
A collection of small libraries / utilities commonly used in building Nexus services.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-service  

### sbt-nexus
This library contains all the code related to the management of our Scala projects using [SBT](http://www.scala-sbt.org/). Its purpose is to promote reuse of build code across all projects.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/sbt-nexus  

## Web applications

### Nexus Dashboard
This web application is the landing page of a Blue Brain Nexus deployment. It gives access to basic statistics about your instance and pointer to other web applications, documentation, tutorials...

**Status**: active development
**Source code**: https://github.com/BlueBrain/nexus-dashboard

### Nexus Search
This web application allows users of the nexus platform to search in the knowledge graph. Beyond searching and inspecting data stored in the platform, its purpose is to enable the discovery of similar and related data.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-search-webapp  

### Nexus Explorer
This web application enables users to securely browse entity and data stored in the knowledge graph by drilling down through organisations, domains, schemas and instances. Once an instance of interest has been found, you can navigate the knowledge graph following entity relations. 

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-explorer  

### Nexus Webapp Commons
This library contains all the code shared by nexus web applications in order to promote their reuse.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-webapp-commons  

### Nexus Registration
This prototypic web application will allow users to enter data into the knowledge graph using forms generated automatically from domain’s schemas. This application is meant for low scale data integration while large scale will be done programmatically through APIs.

**Status**: currently inactive
**Source code**: https://github.com/BlueBrain/nexus-registration-webapp  


## Domains (Schemas, Vocabularies)

### Nexus Core Schemas

#### nexus-schemaorg
[SHACL](https://www.w3.org/TR/shacl/) version of a subset of schemas defined by [schema.org](http://schema.org/docs/full.html) that are commonly used in Blue Brain Nexus.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-schemaorg  

#### nexus-prov
Data management oriented [SHACL](https://www.w3.org/TR/shacl/) version of [W3C PROV-O](http://www.w3.org/ns/prov-o-20130430).

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-prov  

### Nexus domain specific components

#### Nexus BBP Data models
Data models that Blue Brain has developed in order to facilitate the integration of specific neuroscience data.

**Status**: active development  
**Source code**: https://github.com/BlueBrain/nexus-bbp-domains  

#### Neuroshapes
A community effort to develop open SHACL schemas for FAIR (Findable, Accessible, Interoperable, Reproducible) neuroscience data.

**Status**: active development  
**Source code**: https://github.com/INCF/neuroshapes  

## Nexus SDK

### Nexus Python SDK
The Human Brain Project has developed an open source library called Pyxus to facilitate the use of Nexus with Python. 
This library will help Python users to integrate their tools with the Blue Brain Nexus platform. Initially, it will mainly enable the interface with the Knowledge Graph service.

**Status**: active development
**Source code**: https://github.com/HumanBrainProject/pyxus
