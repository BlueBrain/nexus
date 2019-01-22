
# Quick Start


## Overview

This example-driven tutorial presents 5 steps to get started with Blue Brain Nexus to build and query a simple [knowledge graph]().
The goal is to go over some capabilities of Blue Brain Nexus enabling:

* The creation of a project as a protected data space to work with
* An easy ingestion of dataset and management of it's lifecycle
* Querying a dataset to retrieve various information
* Share a dataset by making it public

For that we will work with the small version of the [MovieLens dataset](http://files.grouplens.org/datasets/movielens/ml-latest-small.zip) containing a set of movies (movies.csv) along with their ratings (ratings.csv) and tags (tags.csv) made by users.
An overview of this dataset can be found [here](../dataset/index.html) but it's not required to read it to follow this quick start tutorial.

@@@ note
This tutorial makes use of an AWS deployment of Blue Brain Nexus available at $NEXUS-URL.
@@@

Let's get started.

## Set up

* Download the dataset

The dataset can be downloaded either directly on a browser or using the following  curl command:

```shell
# Go to your home
$ cd ~

# Download the dataset and unzip it in the directory ~/ml-latest-small.
$ curl -s -O http://files.grouplens.org/datasets/movielens/ml-latest-small.zip && unzip -qq ml-latest-small.zip && cd ml-latest-small && ls
README.txt	links.csv	movies.csv	ratings.csv	tags.csv
...
```

* Install and setup the Nexus CLI

```shell
$ pip install nexus-cli
```

* Create and select an 'amld2019' profile

```shell
# Create a profile named amld2019
$ nexus profiles --create amld2019 --url $NEXUS-URL

# The created profile is local to your machine.
$ nexus profiles --select amld2019

# Verify that the 'amld2019' profile is actually selected.
$ nexus profiles
+------------+----------+-------------------------------------+-------+
| Profile    | Selected | URL                                 | Token |
+------------+----------+-------------------------------------+-------+
| amld2019   |   Yes    | $NEXUS-URL                          |  None |
+------------+----------+-------------------------------------+-------+
...
```

* Login with a token

Let login to the AWS Nexus deployment to get a token
```shell
# At this point the profile amld2019 is selected
$ nexus login $NEXUS-URL -t $TOKEN
...
```

TBFD

## Create a project

Projects in BlueBrain Nexus are spaces where data can be **managed** ( **created**, **validated**, **secured**, **updated**, **deprecated**), **accessed** and **shared**.
A project is always created within an organization just like a git repository is created in a github organization.

@@@ note
A public **organization named "amld2019"** is already created for the purpose of this tutorial. All projects will be created under the "amld2019" organization.
@@@

Let first select the amld2019 organization.

```shell
$ nexus organization amld2019
Set current organization to amld2019 on server $NEXUS-URL
```

Create a project in the organization "amld2019 using your [USERNAME].

```shell
$ nexus create project $USERNAME
Created project named "$USERNAME" on organization "amld2019" and server "$NEXUS-URL"
```

By default created projects are private meaning that only the project creator has read and write access. We'll [see below](#make-a-dataset-public) how to make a project public.

The following command shows the list of projects you have access to. The project you just created should be the only one listed at this point.
Note the * character showing the current project you're working in.


```shell
$ nexus projects
Projects on server "$NEXUS-URL":
    * USERNAME
```

The following command displays a short description of the current project.

```shell
 $ nexus project
 Working with project "$USERNAME" on organization "amld2019" and server "$NEXUS-URL"
```

A complete project description can also be displayed by adding the -v (for verbose) flag. A default configuration was generated for the project you just created as you can see.

```shell
 $ nexus project -v
 Working with project "$USERNAME" on organization "amld2019" and server "$NEXUS-URL".
 Project configuration:
    '{
      "@context": "https://bbp-nexus.epfl.ch/staging/v1/contexts/nexus/core/resource/v0.4.0",
      "@id": "https://bbp-nexus.epfl.ch/staging/v1/projects/anorg/$USERNAME",
      "@type": "nxv:Project",
      "_uuid": "714839aa-8262-460a-8b90-dbfff2db7b5c",
      "base": "https://bbp-nexus.epfl.ch/staging/v1/resources/amld2019/$USERNAME/_/",
      "vocab": "ttps://bbp-nexus.epfl.ch/staging/v1/vocabs/amld2019/$USERNAME/",
      "label": "$USERNAME",
      "name": "$USERNAME",
      "prefixMappings": [],
      "_rev": 1,
      "_deprecated": false
    }'
```


## Ingest a dataset

We are all set to bring some data within the project we just created.
Loading a csv file can be performed by running the following simple command:

```
$ nexus create resource -f path/to/file_to_load.csv
```

For the purpose of this tutorial, we will take advantage within Nexus of the dataset data model namely:

* the data types: movies, tags, ratings
* ids

The following command ingest movies in Nexus (from the file movies.csv):

```shell
# {filename:type} is one of: movies, ratings and tags
$ nexus create resource -f ~/ml-latest-small/movies.csv --type Movie
Created 9742 resources of type Movie.
```

From json payload
:   @@snip [resource.sh](../assets/create_from_json.sh)

From json file
:   @@snip [resource.json](../assets/create_json_from_file.sh)

From csv file
:   @@snip [resource-ref-new.json](../assets/create_csv_from_file.sh)



## Query a dataset

### Listing resources

The quickest and simplest way to accessed ingested dataset is by listing them as shown in the following command:

```shell
# List 5 created resources
$ nexus list resources --from=0 --size=5
```

Some filters are available to list specific resources. For example the resources of type Movie can be retrieved by running the following command:
```shell
# List 5 movies (resources of type Movie)
$ nexus list resources --type Movie --from=0 --size=5
```

@@@ note { .tip title="Listing with various filters using the CLI" }

Resources can be filtered from the CLI by any [Nexus added metadata](). As an exercise try to run the listing command above with different filters (like --updatedAt).

@@@

### Search using views

Listing is usually not enough to select specific subset of the data. Data ingested within each project can be queried through two search endpoints called [views]() offering two complementary search features.

View | Accessible at |Description
------------------|-------------
ElasticSearchView |$NEXUS-URL/views/amld2019/movielens/documents/_search|This view exposes data in a document oriented [ElasticSearch]() index and provide access to it using the [ElasticSearch query langugage]().
SparqlView |$NEXUS-URL/views/amld2019/movielens/graph/sparql|This view exposes data as a graph and allows to navigate and traverse the data the [W3C Sparql query langugage]().

Let see some examples of queries using the SparqlView:

Select queries
:   @@snip [resource.json](../assets/sparql_list.sh)

Graph navigation queries
:   @@snip [resource.json](../assets/sparql_nav.sh)

Analytics queries
:   @@snip [resource.json](../assets/sparql_analytics.sh)

Let see some examples of queries using the ElasticSearchView:

Select queries
:   @@snip [resource.json](../assets/sparql_list.sh)

Graph navigation queries
:   @@snip [resource.json](../assets/sparql_list.sh)

Analytics queries
:   @@snip [resource.json](../assets/sparql_list.sh)

## Make a dataset public

Making a dataset public means granting read permissions to "anonymous" user.

```shell
$ nexus grant read on [USERNAME] to anonymous
```

To check that the dataset is now public, logout and run some of the above query examples. You should be able to get result.