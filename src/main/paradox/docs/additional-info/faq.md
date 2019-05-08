# Frequently asked questions


**What is Nexus?**

Nexus is an Open Source, data and knowledge management platform designed to enable the definition of arbitrary applications domains for which there is a need to create and manage entities as well as their relations (e.g. provenance) by the FAIR principles. Indeed, Nexus enable data to be Findable, Accessible, Interoperable and Re-usable, as well as able to track data provenance and supporting data longevity in a secure and scalable manner.


**Is Nexus free to use?**

Yes, Nexus is a free, Open Source platform released under [Apache Licence 2.0](https://opensource.org/licenses/Apache-2.0)


**How to run Nexus?**

There are many ways to run Nexus: a public instance is running at the [Sandbox](https://nexus-sandbox.io/web), meanwhile if you want to run it locally you might need to install [Docker](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/docker.html) or [Minikube](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/minikube.html). You can also deploy Nexus [“on premise”](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/minikube.html), as a single instance or as a cluster.


**How can I try Nexus without installing it? What is the difference with a relational database like PostgreSQL?**

You can use the cloud deployment.
Although Nexus can be used as a regular database, it's flexibility and feature set are well beyond that. 
Just to mention some of the Nexus features:
- Allows to define different constraints to different set of data at runtime;
- Provides automatic indexing into several indexers (currently ElasticSearch and Sparql), dealing with reindexing strategies, retries and progress;
- Provides authentication;
- Comes with a flexible and granular authorization mechanism;
- Guarantees resources immutability, keeping track of a history of changes.


**Is there a cloud deployment of Nexus?**

There is a sandbox for you to try Nexus, it has limited resources and it is regularly wiped out. At the following [page](https://bluebrainnexus.io/docs/getting-started/webapps.html) is explained how to interact with Blue Brain Nexus web interface. 


**Is there a limit on the number of resources Nexus can store?**

Blue Brain Nexus leverages scalable open source technologies, therefore limitations and performance depends heavily on the deployment setup where Nexus is running.

To get an idea about the ingestion capabilities, we have run [Benchmarks](https://bluebrainnexus.io/docs/additional-info/benchmarks/data-volume-and-scenarios.html) where we were able to ingest more than 115 million resources.

**What is a Knowledge Graph?**

Knowledge Graph is an innovative tool Google launched in May 2012 to enhance its search engine results, gathering informations from a variety of sources. It serves as data integration hub, connecting data by their semantic meaning in the ontology form, and allowing a flexible formal data structure organising them as a graph.
As you can see in 'Understanding Knowledge Graph' [page](https://bluebrain.github.io/nexus/docs/tutorial/knowledge-graph/index.html), at the heart of Blue Brain Nexus platform lies Knowledge Graph, that provide knowledge representation to enable FAIR principles at Blue Brain and in neuroscience community.
Indeed, Knowledge Graph Nexus allow scientists to: 
1. register and manage neuroscience relevant entity types; 
2. Submit data to the platform and describe their provenance using the W3C PROV model; 
3. Search, discover, reuse and derive high-quality neuroscience data generated within and outside the platform for the purpose of driving their own scientific endeavours.


**How Nexus support data governance?**


**What has to be configured and how an instance can be integrated with Nexus v1?**


**How do I report a bug? Which support Nexus team provide?**

There are several channels provided to address different issues:
- **Bug report**: If you have found a bug while using some of the Nexus services, please create an issue [here](https://github.com/BlueBrain/nexus/issues/new?labels=bug).
- **Questions**: if you need support, we will be reachable through the [Nexus Gitter channel](https://gitter.im/BlueBrain/nexus)
- **Documentation**: Technical documentation and 'Quick Start' to Nexus related concepts can be found [here](https://bluebrain.github.io/nexus/docs)
- **Feature request**: If there is a feature you would like to see in Blue Brain Nexus, please first consult the [list of open feature requests](https://github.com/BlueBrain/nexus/issues?q=is%3Aopen+is%3Aissue+label%3Afeature). In case there isn't already one, please [open a feature request](https://github.com/BlueBrain/nexus/issues/new?labels=feature) describing your feature with as much detail as possible.

## Technical

**How can I configure my project?**

**How to ingest my data into Nexus**

(csv, json, json-ld, no schema needed)

**What are the clients I can use with Nexus? What are the requirements to run Nexus locally?**

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor. Nexus requires at least 2 CPUs and 8 GiB of memory in total. You can increase the limits in Docker settings in the menu *Preferences > Advanced*. Further information [here](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/docker.html).

**What is JSON-LD?**

JSON-LD is a JavaScript Object Notation for Linked Data. It is a set of W3C standards specifications which enable a semantic-preserving data exchange and allows to solve the ambiguity problem, including a @context object.

**What is RDF?**

The Resource Description Framework (RDF) is a graph-based data model used for representing information in the Web. The basic structure of any expression in RDF is in triples, an extremely easy segmentation of any kind of knowledge in subject-predicate-object. It is a family of W3C specifications, and was originally design as a metadata model.
 
**What is Elasticsearch?**

Elasticsearch is a document oriented search engine with an HTTP web interface and schema-free JSON document. It’s able to aggregate data based on specific queries enabling the exploration of trends and pattern.

**What is a SHACL schema?**

SHACL (Shapes Constraint Language) is a language for validating RDF graphs against a set of conditions. These conditions are provided as shapes and other constructs expressed in the form of an RDF graph.
SHACL is used in Nexus to constrain and control the payload that can be pushed into Nexus.


**Do I need to define SHACL schemas to bring data in?**

No. SHACL schemas provides with an extra layer of quality control for the data that is ingested into Nexus. However we acknowledge the complexity of defining schemas. That's why clients can decide whether to use schemas to constrain their data or not, depending on their use case and their available resources.


**Where can I find SHACL shapes I can reuse (point to resources, like schema.org)?**

In Neuroshape related [page on github](https://github.com/INCF/neuroshapes) are available shared validated data models based on open use cases.

**Why are RDF and JSON-LD important for Nexus?**

RDF is the data model used to ingest data into the Knowledge Graph and it is also used for SHACL schema data validation. JSON-LD is fully compatible with the RDF data model, and it is the main format we use for messages exchange. A JSON-LD payload is then converted to an RDF Graph for validation purposes and for ingestion in the Knowledge Graph.

**Can I connect any SPARQL client to Nexus’ SPARQL endpoint?**

Yes. As long as the client supports the ability to provide a ```Authentication``` HTTP Header (for authentication purposes) on the request, any SPARQL client should work.


**How I can create a Organizations as an anonymous user in the docker-compose file? What needs to be done to switch to "authenticated" mode?**

The permissions for anonymous are preset in the [ACLs](https://bluebrainnexus.io/docs/api/1.0/iam/iam-permissions-api.html) and should be replaced by the standard authentication. More details [here](https://bluebrainnexus.io/docs/api/1.0/iam/iam-permissions-api.html).


**Can I use Nexus from Jupyter Notebooks?**


