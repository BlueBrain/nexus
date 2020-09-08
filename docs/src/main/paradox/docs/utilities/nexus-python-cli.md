# MovieLens Tutorial using the Nexus Python CLI

## Overview

This example-driven tutorial presents 5 steps to get started with Blue Brain Nexus to build and query a simple @ref:[knowledge graph](../getting-started/understanding-knowledge-graphs.md).
The goal is to go over some capabilities of Blue Brain Nexus enabling:

* The creation of a project as a protected data space to work in
* An easy ingestion of a dataset within a given project
* The listing and querying of a dataset
* Sharing a dataset by making it public

@@@ note
This quick start tutorial tutorial makes use of:

* an AWS deployment of Blue Brain Nexus available at https://sandbox.bluebrainnexus.io.
* @link:[Nexus Python CLI](https://github.com/BlueBrain/nexus-cli){ open=new }, a python Command Line Interface.
@@@

Let's get started.

## Set up

Install the Nexus Python CLI:

```python
pip install git+https://github.com/BlueBrain/nexus-cli
```

## Create a project

Projects in BlueBrain Nexus are spaces where data can be:

* **managed**: created, updated, deprecated, validated, secured;
* **accessed**: directly by ids or through various search interfaces;
* **shared**: through fine grain Access Control List.

A **project** is always created within an **organization** just like a git repository is created in a github organization. Organizations can be understood as accounts hosting multiple projects.

### Select an organization

@@@ note
A public organization named **tutorialnexus** is already created for the purpose of this tutorial. All projects will be created under this organization.
@@@

The following command should list the organizations you have access to. The **tutorialnexus** organization should be listed and tagged as non-deprecated in the output.

Command
:   @@snip [list-orgs-cmd.sh](assets/list-orgs-cmd.sh)

Output
:   @@snip [list-orgs-out.sh](assets/list-orgs-out.sh)


Let select the **tutorialnexus** organization.

Command
:   @@snip [select-orgs-cmd.sh](assets/select-orgs-cmd.sh)

Output
:   @@snip [select-orgs-out.sh](assets/select-orgs-out.sh)


In case the tutorialnexus organization is not available, pick an organization label (value of $ORGLABEL) and create an organization using the following command:

Command
:   @@snip [create-org-cmd.sh](assets/create-org-cmd.sh)

Output
:   @@snip [create-org-out.sh](assets/create-org-out.sh)

### Create a project

A project is created with a label and within an organization. The label should be made of alphanumerical characters and its length should be between 3 and 32 (it should match the regex: [a-zA-Z0-9-_]{3,32}).

Pick a label (hereafter referred to as $PROJECTLABEL) and create a project using the following command.
It is recommended to use your username to avoid collision of projects labels within an organization.

Command
:   @@snip [create-project-cmd.sh](assets/create-project-cmd.sh)

Output
:   @@snip [create-project-out.sh](assets/create-project-out.sh)

By default, created projects are private meaning that only the project creator (you) has read and write access to it. We'll @ref:[see below](#share-data) how to make a project public.

The output of the previous command shows the list of projects you have read access to. The project you just created should be the only one listed at this point. Let select it.

Command
:   @@snip [select-project-cmd.sh](assets/select-project-cmd.sh)

Output
:   @@snip [select-project-out.sh](assets/select-project-out.sh)

We are all set to bring some data within the project we just created.

## Ingest data

The CLI supports the ingestion of datasets in two formats: JSON and CSV.


### Ingest JSON
 
#### Ingest JSON from a payload

Command
:   @@snip [downloadmovielens-cmd.sh](assets/create_from_json.sh)


@@@ note
* Note that ingesting a JSON array is not supported. 
@@@

By default Nexus generates an identifier (in fact a URI) for a created resource as shown in the output of the above command.
Furthermore, it is possible to provide:
 
* a specific identifier by setting the **-\-id** option 
* and a type by setting the **-\-type** option
    
Command
  :   @@snip [create_from_json_with_id.sh](assets/create_from_json_with_id.sh)

Output
:   @@snip [create_from_json-out.sh](assets/create_from_json-out.sh)


Identifiers and types can also be provided directly in the JSON payload using respectively: the **@id** and **@type** keys.


The created resource identified by https://movies.com/movieId/1 can then be fetched using the following command :

Command
:   @@snip [fetch-create-json-cmd.sh](assets/fetch-create-json-cmd.sh)

Output
:   @@snip [fetch-create-json-out.sh](assets/fetch-create-json-out.sh)

#### Ingest JSON from a file

A JSON payload can be ingested from a file.

```shell
nexus resources create --file /path/to/file.json
```

A directory (/path/to/dir) of JSON files can be ingested by using the following looping command: 

```shell
find /path/to/dir -name '*.json' -exec  nexus resources create --file {} \;
```

Ingested resources can be listed using the following command:

```shell
nexus resources list --size 10
```

### Ingest CSV files

To illustrate how to load CSV files we will work with the small version of the @link:[MovieLens dataset](http://files.grouplens.org/datasets/movielens/ml-latest-small.zip){ open=new }
containing a set of movies (movies.csv) along with their ratings (ratings.csv) and tags (tags.csv) made by users.
An overview of this dataset can be found [here](https://grouplens.org/datasets/movielens/).

#### Download the dataset

The @link:[MovieLens dataset](http://files.grouplens.org/datasets/movielens/ml-latest-small.zip){ open=new } can be 
downloaded either directly on a browser or using a curl command as shown below.

The following command download, unzip the dataset in the folder ~/ml-latest-small and list the files. The downloaded 
MovieLens dataset is made of four csv files as shown in the output tab.

Command
:   @@snip [downloadmovielens-cmd.sh](assets/downloadmovielens-cmd.sh)

Output
:   @@snip [downloadmovielens-out.sh](assets/downloadmovielens-out.sh)


#### Load the dataset
Let first load the movies and merge them with the links.

```shell
nexus resources create -f ~/ml-latest-small/movies.csv -t Movie --format csv --idcolumn movieId --mergewith ~/ml-latest-small/links.csv --mergeon movieId --max-connections 4

```

Then we can load the tags.

```shell
nexus resources create -f ~/ml-latest-small/tags.csv -t Tag --format csv --max-connections 50
```

And finally load the ratings. Loading 100837 resources might take some time and also it is not needed to load them all to follow this tutorial.
The maximum number of concurrent connections (--max-connections) can be increased for better loading performance.

```shell
nexus resources create -f ~/ml-latest-small/ratings.csv -t Rating --format csv --max-connections 50
```


## Access data

### View data in Nexus Web

Nexus is deployed with a web application allowing to browse organizations, projects, data and schemas you have access to.
You can go to the address https://sandbox.bluebrainnexus.io/web and browse the data you just loaded.

### List data

The simplest way to accessed data within Nexus is by listing them. The following command lists 5 resources:


Command
:   @@snip [list-res-cmd.sh](assets/list-res-cmd.sh)

Output
:   @@snip [list-res-out.sh](assets/list-res-out.sh)

The full payload of the resources are not retrieved when listing them: only identifier, type as well as Nexus added metadata are.
But the result list can be scrolled and each resource fetched by identifier.

Command
:   @@snip [fetch-res-id-cmd.sh](assets/fetch-res-id-cmd.sh)

Output
:   @@snip [fetch-res-id-out.sh](assets/fetch-res-id-out.sh)

Whenever a resource is created, Nexus injects some useful metadata. The table below details some of them:

| Metadata | Description                                                                                                                          | Value Type |
|----------------------|--------------------------------------------------------------------------------------------------------------------------------------|------------|
| @id                  | Generated resource identifier. The user can provide its own identifier.                                                              | URI        |
| @type                | The type of the resource if provided by the user.                                                                                    | URI        |
| \_self               | The resource address within Nexus. It contains the resource management details such as the organization, the project and the schema. | URI        |
| \_createdAt          | The resource creation date.                                                                                                          | DateTime   |
| \_createdBy          | The resource creator.                                                                                                                | DateTime   |

Note that Nexus uses @ref:[JSON-LD](../getting-started/understanding-knowledge-graphs.md#json-ld) as data exchange format.

Filters are available to list specific resources. For example a list of resources of type Rating can be retrieved by running the following command:

Command
:   @@snip [list-res-filter-cmd.sh](assets/list-res-filter-cmd.sh)

Output
:   @@snip [list-res-filter-out.sh](assets/list-res-filter-out.sh)

@@@ note { .tip title="Listing with various filters using the CLI" }

As an exercise try to filter by tag to only retrieve resources of type Tag.

@@@

### Query data

Listing is usually not enough to select specific subset of data. Data ingested within each project can be searched through two complementary search interfaces called @ref:[views](../delta/api/current/views/index.md).

View              | Description
------------------|---------------
ElasticSearchView | Exposes data in @link:[ElasticSearch](https://www.elastic.co/elasticsearch/){ open=new } a document oriented search engine and provide access to it using the @link:[ElasticSearch query language](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html){ open=new }.
SparqlView        | Exposes data as a graph and allows to navigate and explore the data using the @link:[W3C SPARQL query language](https://www.w3.org/TR/sparql11-query/){ open=new }.


@@@ note

Note that the following queries (ElasticSearch and SPARQL) contain the variable $PROJECTLABEL. It should be replaced by the current project.
Please copy each query and use a text editor to replace $PROJECTLABEL.
@@@


#### Query data using the ElasticSearchView

The ElasticSearchView URL is available at the address `https://sandbox.bluebrainnexus.io/v1/views/tutorialnexus/$PROJECTLABEL/documents/_search`.

Select queries
:   @@snip [select_elastic.sh](assets/select_elastic.sh)

Graph navigation queries
:   @@snip [resource.sh](assets/graph_elastic.sh)

#### Query data using the SparqlView

The SparqlView is available at the address `https://sandbox.bluebrainnexus.io/v1/views/tutorialnexus/$PROJECTLABEL/graph/sparql]`.
The following diagram shows how the MovieLens data is structured in the default Nexus SparqlView. Note that the ratings, tags and movies are joined by the movieId property.

Select queries
:   @@snip [select_sparql.sh](assets/select_sparql.sh)

Graph navigation queries
:   @@snip [analytics_sparql.sh](assets/analytics_sparql.sh)

## Share data

Making a dataset public means granting read permissions to "anonymous" user.

```shell
$ nexus acls make-public
```

To check that the dataset is now public:

* Ask the person next to you to list resources in your project.
* Or create and select another profile named public-tutorial (following the instructions in the @ref:[Set up](#set-up).
You should see the that the public-tutorial is selected and its corresponding token column is None.

Output
:   @@snip [select-profile-public-out.sh](assets/select-profile-public-out.sh)


* Resources in your project should be listed with the command even though you are not authenticated.

Command
:   @@snip [list-res-org-proj-cmd.sh](assets/list-res-org-proj-cmd.sh)
