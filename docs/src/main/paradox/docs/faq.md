# Frequently asked questions

### What is Blue Brain Nexus?

Blue Brain Nexus is an Open Source, data and knowledge management platform designed to enable ingestion, integration and search of virtually any kind of data. Entities (real world data) are described using well defined and validatable schemas (using JSON-LD and optionally SHACL). Nexus can be used with existing data schemas from http://schema.org or new ones custom created for your own applications. For neuroscience data, http://Neuroshapes.org provides open, community-developed schemas. While Nexus is engineered to support rich semantics, it is not obligatory. In fact, data can be ingested from existing data sources such as CSV files and SQL databases, and iteratively reshaped into well defined data schemas.

Nexus creates and manages entities in accordance with the FAIR (Findable, Accessible, Interoperable and Re-usable) Principles. The FAIR principles are also applied to data where Nexus provides the ability to track data provenance and supports data longevity in a secure and scalable manner.

### Is Blue Brain Nexus free to use?

Yes, Nexus is a free, Open Source platform released under [Apache Licence 2.0](https://opensource.org/licenses/Apache-2.0)

### How do I run Blue Brain Nexus?

There are many ways to run Nexus. The public instance is running in a [Sandbox](https://sandbox.bluebrainnexus.io/web/), meanwhile if you want to run it locally you might need to install [Docker](getting-started/running-nexus/index.html#docker) or [Minikube](getting-started/running-nexus/index.html#run-nexus-locally-with-minikube). You can also deploy Nexus [“on premise”](getting-started/running-nexus/index.html#on-premise-cloud-deployment), as a single instance or as a cluster. Nexus has also been deployed and tested on AWS and information on deploying in the Amazon cloud will be made available shortly.

### How can I try Blue Brain Nexus without installing it? What is the difference with a relational database like PostgreSQL?

The [Sandbox](https://sandbox.bluebrainnexus.io/web/) provides a public instance that can serve as a testbed. Be aware that the content of the Sandbox is regularly purged.

Although Blue Brain Nexus can be used as a regular database, it's flexibility and feature set are well beyond that. Just to mention some of the Nexus features:

- Allows the user to define different constraints to different set of data at runtime
- Provides automatic indexing into several indexers (currently ElasticSearch and Sparql), dealing with reindexing strategies, retries and progress
- Provides authentication
- Comes with a flexible and granular authorization mechanism
- Guarantees resources immutability, keeping track of a history of changes.

### Is there a cloud deployment of Blue Brain Nexus?

There is a sandbox for you to try Nexus, it has limited resources and it is regularly wiped out. At the following @ref:[page](fusion/index.md) is explained how to interact with Blue Brain Nexus Web interface. It is possible to deploy Nexus on Amazon's AWS infrastructure. Instructions for how to do so will be made available shortly.

### Is there a limit on the number of resources Blue Brain Nexus can store?

Blue Brain Nexus leverages scalable open source technologies, therefore limitations and performance depends heavily on the deployment setup where Nexus is running.

To get an idea about the ingestion capabilities, we have run @ref:[Benchmarks](delta/benchmarks.md) where we were able to ingest over 3.5 billion triples representing 120 million resources.

### What is a Knowledge Graph?

A Knowledge Graph is a modern approach to enabling the interlinked representations of entities (real-world objects, activities or concepts). Knowledge Graphs combine properties of databases (enabling structured queries), graphs (finding and analysing data via entity relationships) and knowledge bases (that enable navigating knowledge and inferring facts).

Blue Brain Nexus employs a Knowledge Graph to enable validation, search, analysis and integration of data.

As you can see in 'Understanding the Knowledge Graph' @ref:[page](getting-started/understanding-knowledge-graphs.md), at the heart of Blue Brain Nexus platform lies a Knowledge Graph, that provides knowledge representation to enable data integration and FAIR principles at Blue Brain and across the neuroscience community.

Indeed, Nexus allow scientists to:

- Register and manage relevant entity types

- Submit data to the platform and describe their provenance using the W3C PROV model

- Search, discover, reuse and derive data generated within and outside the platform for the purpose of driving their own scientific endeavours.

### How do I report a bug? Which support Blue Brain Nexus team provide?

There are several channels provided to address different issues:

- **Bug report**: If you have found a bug while using the Nexus ecosystem, please create an issue [here](https://github.com/BlueBrain/nexus/issues/new?labels=bug).
- **Questions**: if you need support, we will be reachable through the [Nexus Gitter channel](https://gitter.im/BlueBrain/nexus)
- **Documentation**: Technical documentation and 'Quick Start' to Nexus related concepts can be found [here](https://bluebrain.github.io/nexus/docs)
- **Feature request**: If there is a feature you would like to see in Blue Brain Nexus, please first consult the [list of open feature requests](https://github.com/BlueBrain/nexus/issues?q=is%3Aopen+is%3Aissue+label%3Afeature). In case there isn't already one, please [open a feature request](https://github.com/BlueBrain/nexus/issues/new?labels=feature) describing your feature with as much detail as possible.

## Technical

### What are the clients I can use with Blue Brain Nexus? What are the requirements to run Blue Brain Nexus locally?

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor. Nexus requires at least 2 CPUs and 8 GB of memory in total. You can increase the limits in Docker settings in the menu _Preferences > Advanced_. More details are in the dedicated @ref:[page](getting-started/running-nexus/index.md).

### What is JSON-LD?

JSON-LD is a JavaScript Object Notation for Linked Data. A JSON-LD payload is then converted to an RDF Graph for validation purposes and for ingestion in the Knowledge Graph.

### How can I represent lists on JSON-LD?

Using JSON-LD, arrays are interpreted as Sets by default. If you want an array to be interpreted as a list, you'll have to add the proper context for it. If the field containing the array is called `myfield`, then the context to be added is

```json
{
  "@context": {
    "myfield": {
      "@container": "@list"
    }
  }
}
```

You can find more information about Sets and Lists in Json-LD on the [Json-LD 1.0 specification](https://www.w3.org/TR/json-ld/#sets-and-lists)

### What is RDF?

The Resource Description Framework (RDF) is a graph-based data model used for representing information in the Web. The basic structure of any expression in RDF is in triples, an extremely easy segmentation of any kind of knowledge in subject-predicate-object. It is a family of W3C specifications, and was originally designed as a metadata model.

### What is Elasticsearch?

Elasticsearch is a document oriented search engine with an HTTP web interface and schema-free JSON document. It is able to aggregate data based on specific queries enabling the exploration of trends and patterns.

### What is a SHACL schema?

SHACL (Shapes Constraint Language) is a language for validating RDF graphs against a set of conditions. These conditions are provided as shapes and other constructs expressed in the form of an RDF graph. SHACL is used in Blue Brain Nexus to constrain and control the payload that can be pushed into Nexus.

### Do I need to define SHACL schemas to bring data in?

No. SHACL schemas provide an extra layer of quality control for the data that is ingested into Nexus. However we acknowledge the complexity of defining schemas. That's why clients can decide whether to use schemas to constrain their data or not, depending on their use case and their available resources.

### Where can I find SHACL shapes I can reuse (point to resources, like schema.org)?

Datashapes.org provides an automated conversion of schema.org as SHACL entities. A neuroscience community effort and INCF Special Interest Group - Neuroshapes [page on github](https://github.com/INCF/neuroshapes), provides open schemas for neuroscience data based on common use cases.

### Why are RDF and JSON-LD important for Blue Brain Nexus?

RDF is the data model used to ingest data into the Knowledge Graph and it is also used for SHACL schema data validation. JSON-LD is fully compatible with the RDF data model, and it is the main format we use for messages exchange. It is also the preferred format for message exchanges for APIs. The choice of JSON-LD is due to the fact that is as plain JSON but with some special [keywords](https://json-ld.org/spec/latest/json-ld/#syntax-tokens-and-keywords).

### Can I connect any SPARQL client to Nexus’ SPARQL endpoint?

Yes. As long as the client supports the ability to provide a `Authentication` HTTP Header (for authentication purposes) on the request, any SPARQL client should work.

### How can I create an Organizations as an anonymous user in the docker-compose file? What needs to be done to switch to "authenticated" mode?

The permissions for anonymous are pre-set in the @ref:[ACLs](delta/api/current/iam-permissions-api.md) and should be replaced by the standard authentication. More details @ref:[here](delta/api/current/iam-permissions-api.md).

### Can I use Blue Brain Nexus from Jupyter Notebooks?

Blue Brain Nexus can be used from Jupyter Notebooks using [Nexus Python SDK](https://github.com/BlueBrain/nexus-python-sdk/). Alternatively, you can also use any Python HTTP client and use Nexus REST API directly from the Jupyter Notebook. Few examples are provided in the folder [Notebooks](https://github.com/BlueBrain/nexus-python-sdk/tree/master/notebooks) or in the [page](https://github.com/BlueBrain/nexus/blob/master/src/main/paradox/docs/tutorial/notebooks/Recommendation%20System%20via%20Nexus.ipynb) dedicated to create a recommendation engine using Blue Brain Nexus.
