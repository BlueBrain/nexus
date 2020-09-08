# FAQ

## General FAQ

### What is Blue Brain Nexus?

Blue Brain Nexus is an ecosystem that allows you to organize and better leverage your data through the use of a 
Knowledge Graph. You can find out more information on our @link:[product home page](https://bluebrainnexus.io/){ open=new }. 

### Is Blue Brain Nexus free to use?

Yes, Nexus is a free, Open Source platform released under @link:[Apache Licence 2.0](https://opensource.org/licenses/Apache-2.0){ open=new }

### How do I run Blue Brain Nexus?

There are many ways to run Nexus. Our public Sandbox is running @link:[here](https://sandbox.bluebrainnexus.io/web/){ open=new } 
and you can use it to test Nexus on small, non-sensitive data. Our @ref:[tutorial](getting-started/try-nexus.md) can 
help you to run Nexus step by step.

Meanwhile if you want to run it locally you might need to install @ref:[Docker](getting-started/running-nexus.md#docker) 
or @ref:[Minikube](getting-started/running-nexus.md#run-nexus-locally-with-minikube). You can also deploy Nexus 
@ref:[“on premise”](getting-started/running-nexus.md#on-premise-cloud-deployment), as a single instance or as a cluster. 
Blue Brain Nexus has also been deployed and tested on AWS using @link:[Kubernetes](https://kubernetes.io/){ open=new }.

### How can I try Blue Brain Nexus without installing it? 

The @link:[Sandbox](https://sandbox.bluebrainnexus.io/web/){ open=new } provides a public instance that can serve as a 
testbed. Be aware that the content of the Sandbox is regularly purged.

### What is the difference with a relational database like PostgreSQL?

Although Blue Brain Nexus can be used as a regular database, it's flexibility and feature set are well beyond that. 
Just to mention some of the Nexus features:

- Allows the user to define different constraints to different set of data at runtime
- Provides automatic indexing into several indexers (currently ElasticSearch and Sparql), dealing with reindexing 
strategies, retries and progress
- Provides authentication
- Comes with a flexible and granular authorization mechanism
- Guarantees resources immutability, keeping track of a history of changes.

### Is there a limit on the number of resources Blue Brain Nexus can store?

Blue Brain Nexus leverages scalable open source technologies, therefore limitations and performance depends heavily on 
the deployment setup where Nexus is running.

To get an idea about the ingestion capabilities, we have run @ref:[Benchmarks](delta/benchmarks.md) where we were able 
to ingest over 3.5 billion triples representing 120 million resources.

### What is a Knowledge Graph?

A Knowledge Graph is a modern approach to enabling the interlinked representations of entities (real-world objects, 
activities or concepts). In order to find more information about Knowledge Graphs, please visit the section 
@ref:["Understanding the Knowledge Graph"](getting-started/understanding-knowledge-graphs.md)

Blue Brain Nexus employs a Knowledge Graph to enable validation, search, analysis and integration of data.

### How do I report a bug? Which support Blue Brain Nexus team provide?

There are several channels provided to address different issues:

- **Bug report**: If you have found a bug while using the Nexus ecosystem, please create an issue 
  @link:[here](https://github.com/login?return_to=https%3A%2F%2Fgithub.com%2FBlueBrain%2Fnexus%2Fissues%2Fnew%3Flabels%3Dbug){ open=new }.
- **Questions**: if you need support, we will be reachable through the @link:[Nexus Gitter channel](https://gitter.im/BlueBrain/nexus){ open=new }
- **Documentation**: Technical documentation and 'Quick Start' to Nexus related concepts can be found 
  @link:[here](https://bluebrainnexus.io/docs/){ open=new }
- **Feature request**: If there is a feature you would like to see in Blue Brain Nexus, please first consult the 
  @link:[list of open feature requests](https://github.com/BlueBrain/nexus/issues?q=is%3Aopen+is%3Aissue+label%3Afeature){ open=new }. 
  In case there isn't already one, please 
  @link:[open a feature request](https://github.com/login?return_to=https%3A%2F%2Fgithub.com%2FBlueBrain%2Fnexus%2Fissues%2Fnew%3Flabels%3Dfeature){ open=new } describing 
  your feature with as much detail as possible.

## Technical FAQ  

### What are the clients I can use with Blue Brain Nexus? What are the requirements to run Blue Brain Nexus locally?

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor. Nexus requires 
at least 2 CPUs and 8 GB of memory in total. You can increase the limits in Docker settings in the menu 
_Preferences > Advanced_. More details are in the dedicated @ref:[page](getting-started/running-nexus.md).

### What is JSON-LD?

JSON-LD is a JavaScript Object Notation for Linked Data. A JSON-LD payload is then converted to an RDF Graph for 
validation purposes and for ingestion in the Knowledge Graph. In order to find more information about JSON-LD, please 
visit this page, please visit the section @ref:[this page](getting-started/understanding-knowledge-graphs.md#json-ld)

### How can I represent lists on JSON-LD?

Using JSON-LD, arrays are interpreted as Sets by default. If you want an array to be interpreted as a list, you will 
have to add the proper context for it. For example, if the field containing the array is called `myfield`, then the 
context to be added would be:

```json
{
  "@context": {
    "myfield": {
      "@container": "@list"
    }
  }
}
```

You can find more information about Sets and Lists in JSON-LD on the 
@link:[Json-LD 1.0 specification](https://www.w3.org/TR/json-ld/#sets-and-lists){ open=new }

### What is RDF?

The Resource Description Framework (RDF) is a graph-based data model used for representing information in the Web. 
The basic structure of any expression in RDF is in triples, an extremely easy segmentation of any kind of knowledge 
in subject-predicate-object. It is a family of W3C specifications, and was originally designed as a metadata model. 
In order to find more information about RDF and JSON-LD, please visit this page, please visit the section 
@ref:[this page](getting-started/understanding-knowledge-graphs.md#rdf)

### What is Elasticsearch?

@link:[Elasticsearch](https://www.elastic.co/elastic-stack){ open=new } is a document oriented search engine with an 
HTTP endpoint and schema-free JSON document. It is able to aggregate data based on specific queries enabling the 
exploration of trends and patterns.

### What is a SHACL schema?

@link:[SHACL](https://www.w3.org/TR/shacl/){ open=new } (Shapes Constraint Language) is a language for validating RDF 
graphs against a set of conditions. These conditions are provided as shapes and other constructs expressed in the form 
of an RDF graph. SHACL is used in Blue Brain Nexus to constrain and control the payload that can be pushed into Nexus. 
You can use the @link:[SHACL Playground](https://shacl.org/playground/){ open=new } to test your schemas.

### Do I need to define SHACL schemas to bring data in?

No. @link:[SHACL](https://www.w3.org/TR/shacl/){ open=new } schemas provide an extra layer of quality control for the 
data that is ingested into Nexus. However, we acknowledge the complexity of defining schemas. That's why clients can 
decide whether to use schemas to constrain their data or not, depending on their use case and their available resources.

### Where can I find SHACL shapes I can reuse (point to resources, like schema.org)?

@link:[Datashapes.org](http://datashapes.org/){ open=new } provides an automated conversion of 
@link:[schema.org](https://schema.org/){ open=new } as SHACL entities. A neuroscience community effort and INCF Special 
Interest Group - @link:[Neuroshapes](https://github.com/INCF/neuroshapes){ open=new }, provides open schemas for 
neuroscience data based on common use cases.

### Why are RDF and JSON-LD important for Blue Brain Nexus?

RDF is the data model used to ingest data into the Knowledge Graph and it is also used for SHACL schema data validation. 
JSON-LD is an RDF concrete syntax, and it is the main format we use for messages exchange. The choice of JSON-LD is due 
to the fact that is plain JSON but with some special 
@link:[keywords](https://www.w3.org/TR/json-ld11/#syntax-tokens-and-keywords){ open=new } and JSON is a broadly 
adopted API exchange format.

### Can I connect any SPARQL client to Nexus’ SPARQL endpoint?

Yes. As long as the client supports the ability to provide a `Authentication` HTTP Header (for authentication purposes) 
on the HTTP request, any SPARQL client should work.

### How can I create an organization as an anonymous user in the docker-compose file? What needs to be done to switch to "authenticated" mode?

By default, the permissions used - for an authenticated user - when running Nexus Delta are the ones defined on the JVM 
property @link:[app.permissions.minimum](https://github.com/BlueBrain/nexus/blob/v1.4.1/delta/src/main/resources/app.conf#L213){ open=new }.
In order to change that behaviour, please create some ACLs for the path `/`. For more details about ACLs creation, 
visit the @ref:[ACLs page](delta/api/current/iam-acls-api.md#create-acls).

### Can I use Blue Brain Nexus from Jupyter Notebooks?

Blue Brain Nexus can be used from Jupyter Notebooks using 
@link:[Nexus Forge](https://github.com/blueBrain/nexus-forge){ open=new } or 
@link:[Nexus Python SDK](https://github.com/BlueBrain/nexus-python-sdk/){ open=new }. Alternatively, you can also use 
any Python HTTP client and use Nexus REST API directly from the Jupyter Notebook.
Please consider looking at our @ref:[tutorial](getting-started/try-nexus.md) to learn how to user Nexus Forge on the 
Sandbox. Other examples are provided in the folder 
@link:[Notebooks](https://github.com/BlueBrain/nexus-python-sdk/tree/master/notebooks){ open=new }.
