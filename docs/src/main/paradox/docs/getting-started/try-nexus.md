# Try Nexus

## Step 0: Python Setup

In this tutorial, you are encouraged to run Nexus Forge to interact with Nexus. Nexus Forge is a python library. We provide Python Notebooks with examples.

You can either run the notebook in Binder or Google Colaboratory, or run it locally.

### Running Python and Jupyter Locally

Here is a step-by-step guide to setup your Python environment to work with Nexus Forge, Jupyter, and the Allen brain SDK.

This is not mandatory, feel free to use your preferred method.

#### Install Miniconda

Go to [Miniconda](https://docs.conda.io/en/latest/miniconda.html) and download and install the latest for your operating system.

#### Create an environment

Open the terminal and type:

```bash
conda create -yn kgforge python=3.7
```

> The Allen SDK is rated for Python 3.6 and 3.7. You can try installing a more recent version of Python but it might not work. Nexus Forge works with Python 3.6 and above.

#### Switch to the environment

In the terminal, type:

```bash
conda activate kgforge
```

### Install the Nexus Forge

In the terminal, with the environment activte, type:

```bash
pip install nexusforge
```

You can also install the development version directly from GitHub:

```bash
pip install git+https://github.com/BlueBrain/nexus-forge.git@<branch-name>
```

#### Install Additional Packages

To avoid any issues, install jupyter in your environment.

```bash
pip install jupyterlab
```

You will need the Allen SDK to get data from their database.

```bash
pip install allensdk
```

> allensdk 2.11.2 requires pandas<=0.25.3,>=0.25.1, but you have pandas 1.2.5 which is incompatible.

To work with morphologies, you will need Blue Brain's NeuroM package. This will install Blue Brain's MorphIO as well.

```bash
pip install neurom
```

> neurom 2.3.1 requires pandas>=1.0.5, but you have pandas 0.25.3 which is incompatible.

#### Get and Install Mappers (not open source)

```bash
git clone https://bbpcode.epfl.ch/code/dke/kgforge-mappers
```

```bash
pip install ./kgforge-mappers
```

### Running Notebooks in Binder or Google Colaboratory

## Step 1: Nexus Setup

We run the latest version of Nexus publicly for education. You can also use the instance in your organization (if any), or setup Nexus from scratch.

### 1.1. Using the Sandbox

The [Sandbox](https://sandbox.bluebrainnexus.io/web/) is a deployment of Nexus, managed by the Blue Brain Neuroinformatics team, for educational purposes.

Once on the Sandbox homepage, click on the top right corner on the login button.

@@@ div { .center }
![Sandbox Home](../assets/tutorial-sandbox-login.jpeg)
@@@

We offer for the moment two identity providers (IdP):

- Github
- ORCID

You'll need an account on either one to be able to continue this tutorial.

Click on the IdP of your choice and allow the Nexus to have access. You are now logged in.

Once logged in, you can get your token. The token is your secure, private, code that you will use in the rest of this tutorial to interact with Nexus.

### 1.2. Running your Own Instance of Nexus

We do not recommend to setup your own instance for this tutorial. If you are interested to install your own instance, [check our guide](LINK HERE).

## Step 2: Download and Register Data from One Cell
## Step 2: Downloading Data from Allen and Mouselight

### 2.1. Data vs Metadata

When we talk about data, we're mostly talking about binary or text files. When we hear metadata, it means "data about the data".

In neuroscience, you will have images of brain slices. You will have images of stained celles. You willl find files of 3D-morphologies or electrophysiology recordings of the cell or neuron.

The brain is big, and the amount of data collected grows every second.

As a result, it quickly become extremely difficult for groups of scientists to manage, track, share, and work with this data.

To solve this growing pain, we can add metadata, or context, to the data collected. Specifically for neuroscience, we designed MINDS. MINDS is the Minimal Information about a Neuroscience DataSet.

@@@ div { .center }
![MINDS Diagram](../assets/MINDS_diagram.png)
@@@

In the diagram above, you can see that your data (which we will call [Dataset](https://schema.org/Dataset) from now on) has now additional properties:

- Subject: Species, age etc. of subject from which dataset was generated
- Brain Location: Brain region or 3D coordinates within a brain atlas
- Data Types: Type of the data stored in the dataset
- Contibutions: The agent (scientists, organizations) to whom the dataset is attributed to
- Distribution: Direct link to the dataset binaries (downloadURL) or web page describing how to download them (accessURL)
- License: Dataset license (e.g. CC BY 4.0)

You can check the details of MINDS by visiting [Neuroshapes](https://incf.github.io/neuroshapes/). Neuroshapes provide open schemas for [F.A.I.R.](https://en.wikipedia.org/wiki/FAIR_data) neuroscience data.

### 2.2. Provenance

You have just seen how we can add metadata to our datasets to give more context. This allows scientists to find their and their peers' data more easily.

Another important factor to consider is where the data comes from, i.e. what experiment genearted it, who condutected the experiment, and what data was used to derive the new data.

Luckily for us, some good folks have defined a [provenance data model](https://www.w3.org/TR/prov-o/) that we can use.

@@@ div { .center }
![PROV Diagram](../assets/PROV_diagram.png)
@@@

The diagram above is the basic representation of provenance. Our Dataset is an extension of the Entity. The Entity itself was generated by an Activity, which itself used (or not) another Entity. Finally, the Activity is associated with an Agent.

There are ways to add more details, for example with "qualified" relations. You can read more about it in the [W3C PROV specification](https://www.w3.org/TR/prov-o/).

### 2.3. Allen Brain Institute Data

The goal of the Allen Institute for Brain Science is to accelerate the understanding of how the brain works.

The Allen Institute for Brain Science has its data (mouse and human brains) available online for free. For the purpose of this tutorial, we're mostly interested in Cells. Check out their [data portal](https://celltypes.brain-map.org/).

You can for example access a [cell's morphology page](https://celltypes.brain-map.org/experiment/morphology/614777438) and browse and download the morphology data. Or you can head to the [cell's electrophysiology page](https://celltypes.brain-map.org/experiment/electrophysiology/614777438) to do the same.

You can either download and get the required information directly from their portal, or use the Allen SDK (Software Development Kit) to do so from your Python script (as shown in the example notebooks).

### 2.4. MouseLight Data

The MouseLight project generates datasets of whole mouse brains.

You can access the [MouseLight data](http://ml-neuronbrowser.janelia.org/) directly in the browser. You can also access it programmaticaly through different endpoints, such as [GraphQL](http://ml-neuronbrowser.janelia.org/graphql/).

The example notebooks will use these endpoints to collect and download the datasets and their metadata.

### 2.5. Download and Register Data in Nexus

## Step 3: Download and Register Multiple Cells
## Step 3: Registering Data in Nexus

### 3.1. Mappers

### 3.2. Linking Data on the Web

[schema.org](https://schema.org/)

## Step 4: Organize and Visualize Cells in Nexus Fusion

Nexus Fusion alows you to browse the data stored in Nexus. Our extensible architecture also enables the development of visualization plugins.

### 4.1. SPARQL and RDF

[SPARQL](https://www.w3.org/TR/sparql11-query/) is an RDF query language. In other words, it's a way to request a subset of data from a database that conforms to the [RDF (Resource Description Framework)](https://en.wikipedia.org/wiki/Resource_Description_Framework) model.

When writing data to Nexus, the different payloads are stored in a long table, each row representing a resource. The data is then synced to our RDF database and thus converted to a graph. When reading the data, you can query this graph with SPARQL.

Let us take a JSON-LD example payload:

```json
{
  "@type": [
    "http://schema.org/Dataset",
    "http://www.w3.org/ns/prov#Entity"
  ],
  "http://schema.org/description": "My first dataset",
  "http://schema.org/name": "Dataset",
  "http://schema.org/distribution": [
    {
      "@type": "http://schema.org/DataDownload",
      "http://schema.org/contentUrl": {
        "@id": "https://bbp.epfl.ch/nexus/v1/resources/nise/ulbrich1/_/cfb62f82-6d54-4e35-ab5e-3a3a164a04fb"
      },
      "http://schema.org/name": "mesh-gradient.png"
    }
  ]
}
```

You can copy and paste this JSON-LD payload in the [JSON-LD Playground](https://json-ld.org/playground/) and see the expanded results.

Here's what it would look like as an RDF graph:

@@@ div { .center }
![RDF Graph of JSON Payload](../assets/json-to-graph.png)
@@@

The graph is composed of triples of the form `Subject - predicate -> Object`. In the example above, `_:b0` would be a subject (in our case the payload, `_:b0` is just an arbitrary ID), `<http://schema.org/name>` is a predicate, and `Dataset` an object.

If we want to query this graph and return only the name and description of `Dataset` data types, we can write a simple SPARQL query:

```sparql
SELECT ?name ?description
WHERE {
    ?resource <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://schema.org/Dataset> ;
              <http://schema.org/name> ?name ;
              <http://schema.org/description> ?description .
}
```

This would result in the following table:

| name | description |
|------|-------------|
| Dataset | My first dataset |

If there were more resources that matched the same graph patterns, the query would have returned them as well.

#### 4.1.1. (Optional) Improving JSON-LD: ID, Context and More

The JSON-LD payload above is quite verbose. Let's have a look at the different ways to improve it.

The first thing to notice is that if I want to reference my dataset, I don't have an identifier. The node in the graph is a blank node `_:b0`. I can easily add an ID like this:

```json
{
  "@id": "http://example.com/my_gradient_dataset",
  "@type": [
    "http://schema.org/Dataset",
    "http://www.w3.org/ns/prov#Entity"
  ],
  "http://schema.org/description": "My first dataset",
  "http://schema.org/name": "Dataset",
  "http://schema.org/distribution": [
    {
      "@type": "http://schema.org/DataDownload",
      "http://schema.org/contentUrl": {
        "@id": "http://example.com/cfb62f82-6d54-4e35-ab5e-3a3a164a04fb"
      },
      "http://schema.org/name": "mesh-gradient.png"
    }
  ]
}
```

Instead of `_:b0`, the node will be identified by `http://example.com/my_gradient_dataset`. The `@id` uniquely identifies node objects.

Can we now make the JSON-LD less verbose and easier to read? Yes, by defining a context. A context defines the short-hand names used in the JSON-LD payload. In particular, a context can contain a `vocab`.

```json
{
  "@context" : {
    "@vocab" : "http://schema.org/"
  },
  "@id" : "http://example.com/my_gradient_dataset",
  "@type": [
    "Dataset",
    "http://www.w3.org/ns/prov#Entity"
  ],
  "description": "My first dataset",
  "name": "Dataset",
  "distribution": [
    {
      "@type": "DataDownload",
      "contentUrl": {
        "@id": "http://example.com/cfb62f82-6d54-4e35-ab5e-3a3a164a04fb"
      },
      "name": "mesh-gradient.png"
    }
  ]
}
```

If you copy the above snippet to the [JSON-LD Playground](https://json-ld.org/playground/) and look at the expanded form, you'll notice that the properties all expand with  the `http://schema.org/` prefix. Don't hesitate to do the same for the ones below. Play a little with the payload to see what happens to the expanded form.

But what if we want to shorten specific values? We can add them in the context as well.

```json
{
  "@context" : {
    "@vocab" : "http://schema.org/",
    "Entity" : "http://www.w3.org/ns/prov#Entity"
  },
  "@id" : "http://example.com/my_gradient_dataset",
  "@type": [
    "Dataset",
    "Entity"
  ],
  "description": "My first dataset",
  "name": "Dataset",
  "distribution": [
    {
      "@type": "DataDownload",
      "contentUrl": {
        "@id": "http://example.com/cfb62f82-6d54-4e35-ab5e-3a3a164a04fb"
      },
      "name": "mesh-gradient.png"
    }
  ]
}
```

Finally, the last improvement would be to shorten our IDs. For this we can use a base.

```json
{
  "@context" : {
    "@base" : "http://example.com/",
    "@vocab" : "http://schema.org/",
    "Entity" : "http://www.w3.org/ns/prov#Entity"
  },
  "@id" : "my_gradient_dataset",
  "@type": [
    "Dataset",
    "Entity"
  ],
  "description": "My first dataset",
  "name": "Dataset",
  "distribution": [
    {
      "@type": "DataDownload",
      "contentUrl": {
        "@id": "cfb62f82-6d54-4e35-ab5e-3a3a164a04fb"
      },
      "name": "mesh-gradient.png"
    }
  ]
}
```

By default, in Nexus, the base (resp. vocab) defaults to your project, e.g. `https://bbp.epfl.ch/nexus/v1/resources/nise/ulbrich1/_/` (resp. `https://bbp.epfl.ch/nexus/v1/vocabs/nise/ulbrich1/`).

> To check because a property does not expand to the default project vocab if it is not present in the payload context.

The context can also point to another resource so that it is defiend once and can be re-used in multiple resources. In Nexus, a default context for the [Nexus-specific metadata](https://bluebrain.github.io/nexus/contexts/metadata.json) is defined.

There's much more to the [JSON-LD syntax](https://w3c.github.io/json-ld-syntax/). Don't hesitate to have a look for a more detailed explanation.

#### 4.1.2. (Optional) Improving SPARQL: Prefixes

### 4.2. Project and Resource Views

As you saw in the example above, we can use SPARQL to query the cells in our Nexus project.

Let's start by accessing your Nexus instance or the [Sandbox](https://sandbox.bluebrainnexus.io/web/). Go to Admin (from the left hand side menu), and navigate to to your organization and project.

> In the Sandbox, the organization corresponds to the identity provider used, and the project to your username.

In the Project view, you will have the list of all resources that you've registered within your project. You can filter by type or search for a specific term in the name, label, or description.

ADD SCREENSHOT OF PROJECT VIEW

Click on a resource to open the Resource view.

ADD SCREENSHOT OF RESOURCE VIEW

Depending on the resource data type, you might see one or more "plugins". Plugins are components that will show up for specific resources or properties. For example, if you registered a neuron morphology and the data is properly attached trhough the distribution, you'll be able to see a 3D morphology browser plugin.

ADD SCREENSHOT OF NEURON MORPHO PLUGIN

More importantly, you will find the Admin plugin at the bottom of the view. Expand it and you'll see the actual resource payload stored by Nexus, and navigate the graph through links, or visualize the surrounding graph in the graph tab.

ADD SCREENSHOT OF ADMIN

Here's an example of a neuron morpgology resource registered previously:

```json
{
  "@context": "https://bbp.neuroshapes.org",
  "@type": [
    "Datatset,",
    "NeuronMorphology"
  ],
  "apicalDendrite": "spiny",
  "brainLocation": {
    "@type": "BrainLocation",
    "brainRegion": {
      "@id": "mba:778",
      "label": "VISp5"
    },
    "coordinatesInBrainAtlas": {
      "valueX": 8766.93193131801,
      "valueY": 1245.0648598059,
      "valueZ": 8245.14719127383
    },
    "hemisphere": "left",
    "layer": "5"
  },
  "cell_reporter_status": "positive",
  "contribution": {
    "@type": "Contribution",
    "agent": {
      "@id": "https://www.grid.ac/institutes/grid.417881.3",
      "@type": "Organization"
    }
  },
  "csl__normalized_depth": 0.494978352253461,
  "distribution": {
    "@type": "DataDownload",
    "atLocation": {
      "@type": "Location",
      "store": {
        "@id": "nxv:diskStorageDefault"
      }
    },
    "contentSize": {
      "unitCode": "bytes",
      "value": 93718
    },
    "contentUrl": "https://bbp.epfl.ch/nexus/v1/files/dke/kgforge/3f4499f6-87d2-499e-a67c-9922d0c69b08",
    "digest": {
      "algorithm": "SHA-256",
      "value": "1cc575aced64eaebb17b356385e5ea8a35a7609717d52f58ab264c2870d17aac"
    },
    "encodingFormat": "application/swc",
    "name": "reconstruction.swc"
  },
  "donor__disease_state": "",
  "donor__race": "",
  "donor__years_of_seizure_history": "",
  "identifier": 314804042,
  "license": {
    "@id": "https://alleninstitute.org/legal/terms-use/",
    "@type": "License"
  },
  "name": "Rorb-IRES2-Cre-D;Ai14-168053.05.01.01",
  "nr__average_contraction": 0.890830812861332,
  "nr__average_parent_daughter_ratio": 0.837657988583219,
  "nr__max_euclidean_distance": 422.170626147995,
  "nr__number_bifurcations": 28,
  "nr__number_stems": 4,
  "nr__reconstruction_type": "dendrite-only",
  "objectOfStudy": {
    "@id": "http://bbp.epfl.ch/neurosciencegraph/taxonomies/objectsofstudy/singlecells",
    "@type": "ObjectOfStudy",
    "label": "Single Cell"
  },
  "subject": {
    "@type": "Subject",
    "age": {
      "period": "Post-natal",
      "unitCode": "",
      "value": ""
    },
    "identifier": 313403626,
    "name": "Rorb-IRES2-Cre-D;Ai14(IVSCC)-168053",
    "sex": "",
    "species": "Mus musculus",
    "strain": "Rorb-IRES2-Cre"
  },
  "tag__apical": "intact"
}
```

EXPLAIN CONTEXT, REPLACE JSON ONCE EXAMPLE DONE MYSELF

### 4.3. Query Neuroscience Data

Going back to the project view, you'll notice a link to the `SPARQL Query Editor`. Let's click on it and start experimenting.

We want to list the morphologies that we previously registered in our project.

```sparql

```



```sparql
PREFIX nxv: <https://bluebrain.github.io/nexus/vocabulary/>
PREFIX nsg: <https://neuroshapes.org/>
PREFIX schema: <http://schema.org/>
PREFIX prov: <http://www.w3.org/ns/prov#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
SELECT DISTINCT ?self ?name  ?brainRegion ?mtype ?subjectSpecies (CONCAT(STR(?ageValue), " ", ?ageUnit, " ", ?agePeriod) AS ?subjectAge) (GROUP_CONCAT(DISTINCT ?cont; SEPARATOR=", ") AS ?contributor)

WHERE {
?entity nxv:self ?self ;
        a nsg:NeuronMorphology ;
        schema:name ?name ;
        nxv:createdBy ?registered_by ;
        nxv:createdAt ?registeredAt ;
        schema:distribution / schema:encodingFormat "application/swc" ;
        nxv:deprecated false .
  GRAPH ?g { OPTIONAL {
      ?entity nsg:brainLocation / nsg:brainRegion / rdfs:label ?brainRegion } } .
  BIND (STR(?registered_by) AS ?registered_by_str) .
  OPTIONAL { ?entity nsg:annotation / nsg:hasBody / rdfs:label ?mtype } .
  OPTIONAL { ?entity nsg:subject / nsg:species / rdfs:label ?subjectSpecies } .
  ?entity nsg:contribution / prov:agent ?agent .
  OPTIONAL { ?agent schema:givenName ?givenName } .
  OPTIONAL { ?agent schema:familyName ?familyName } . 
  OPTIONAL { ?agent schema:name ?orgName } .
  OPTIONAL { ?entity nsg:subject / nsg:age / schema:value ?ageValue } .
  OPTIONAL { ?entity nsg:subject / nsg:age / schema:unitCode ?ageUnit } .
  OPTIONAL { ?entity nsg:subject / nsg:age / nsg:period ?agePeriod } . 
  BIND(COALESCE(?orgName, CONCAT(?givenName, " ", ?familyName)) AS ?cont ) .
        
} 
GROUP BY ?self ?entity ?name ?registered_by ?registeredAt ?registered_by_str ?g ?registeredBy ?brainRegion ?mtype ?ageValue ?ageUnit ?agePeriod ?subjectAge ?subjectSpecies
LIMIT 1000     
```


### 4.4. Create a Studio

