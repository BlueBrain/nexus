# Frequently asked questions


**What is Nexus?**

Nexus is an Open Source, data and knowledge management platform designed to enable the definition of arbitrary applications domains for which there is a need to create and manage entities as well as their relations (e.g. provenance) by the FAIR principles. Indeed, Nexus enable data to be Findable, Accessible, Interoperable and Re-usable, as well as able to track data provenance and supporting data longevity in a secure and scalable manner.


**Is Nexus free to use?**

Yes, Nexus is a free, Open Source platform released under [Apache Licence 2.0](https://opensource.org/licenses/Apache-2.0)


**How to run Nexus?**

There are many ways to run Nexus: a public instance is running at the [Sandbox](https://nexus-sandbox.io/web), meanwhile if you want to run it locally you might need to install [Docker](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/docker.html) or [Minikube](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/minikube.html). You can also deploy Nexus [“on premise”](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/minikube.html), as a single instance or as a cluster.


**How can I try Nexus without installing it? What is the difference with a regular database like PostgreSQL?**



**Is there a cloud deployment of Nexus?**

There is a sandbox for you to try Nexus, it has limited resources and is regularly wiped out. Further information [here](https://bluebrain.github.io/nexus/docs/webapps/index.html)


**Is there a limit on the number of resources Nexus can store?**



**What is a Knowledge Graph?**

Knowledge Graph is an innovative tool that Google launched in May 2012 to enhance its search engine results, gathering informations from a variety of sources. It serves as data integration hub to connect data by their semantic meaning, in the form of the ontology, and organise them as a graph, allowing a flexible formal data structure.
At the heart of Blue Brain Nexus platform lies Knowledge Graph, that provide knowledge representation to enable FAIR principles at Blue Brain and in neuroscience community.
Indeed, Knowledge Graph Nexus allow scientists to: 1. register and manage neuroscience relevant entity types; 2. Submit data to the platform and describe their provenance using the W3C PROV model; 3. Search, discover, reuse and derive high-quality neuroscience data generated within and outside the platform for the purpose of driving their own scientific endeavours.
It is explained in 'Understanding Knowledge Graph' [page](https://bluebrain.github.io/nexus/docs/tutorial/knowledge-graph/index.html).


**How Nexus support data governance?**


**What has to be configured and how an instance can be integrated with Nexus v1?**


**How do I report a bug? What information should I include?**

If you have found a bug while using some of the Nexus services, please create an issue labelled “bug” in the BlueBrain/nexus repo on GitHub [here](https://github.com/BlueBrain/nexus/issues/new?labels=bug). You have to include all the details about your case.


**Which support Nexus team provide?**



## Technical

**How can I configure my project?**

**How to ingest my data into Nexus**

(csv, json, json-ld, no schema needed)

**I uploaded my data, but the system is not working. What can I do?**

If ‘Replicas’ is 0/1 there’s a good chance that docker is still pulling the data.
Then, if you run docker stack ps nexus you can see if everything is up.


**What are the clients I can use with Nexus? What are the requirements to run Nexus locally?**

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor. Nexus requires at least 2 CPUs and 8 GiB of memory in total. You can increase the limits in Docker settings in the menu *Preferences > Advanced*. Further information [here](https://bluebrain.github.io/nexus/docs/getting-started/running-nexus/docker.html).


**Where is the sandbox deployed?**


**What is a SHACL schema?**



**How does SHACL compares with JSON Schema?**



**Do I need to define SHACL schemas to bring data in?**



**Where can I find SHACL shapes I can reuse (point to resources, like schema.org)?**



**What is JSON-LD?**

JSON-LD is a JavaScript Object Notation for Linked Data. It is a set of W3C standards specifications which enable a semantic-preserving data exchange and allows to solve the ambiguity problem including a @context object, where every key is associated with an identifier. In this way, the object properties are linked to contexts in a JSON document to concepts in ontologies. 
JSON-LD is fully compatible with the RDF data model.

**What is RDF?**

The Resource Description Framework (RDF) is a graph-based data model used for representing information in the Web. The basic structure of any expression in RDF is in triples, an extremely easy segmentation of any kind of knowledge in subject-predicate-object. It is a family of W3C specifications, and was originally design as a metadata model.
 
**What is Elasticsearch?**

Elasticsearch is a document oriented search engine with an HTTP web interface and schema-free JSON document. It’s able to aggregate data based on specific queries enabling the exploration of trends and pattern.


**Why are RDF and JSON-LD important for Nexus?**

Nexus is providing (for free) a REST HTTP API, an Elasticsearch indexation and a SPARQL endpoint if you already have RDF data.


**Can I connect any SPARQL client to Nexus’ SPARQL endpoint?**




**How I can create a Organizations as an anonymous user in the docker-compose file? What needs to be done to switch to "authenticated" mode?**

The permissions for anonymous are preset in the ACLs and should be replaced with the standard API. More details [here](https://bluebrain.github.io/nexus//docs/api/iam/iam-permissions-api.html).


**Can I use Nexus from Jupyter Notebooks?**


