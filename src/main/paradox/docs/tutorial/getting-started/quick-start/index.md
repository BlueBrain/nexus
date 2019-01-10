
# Quick Start


## Overview

This example-driven tutorial presents 5 steps to get started with Blue Brain Nexus to build and query a simple knowledge graph.
We will work with the small version of the [MovieLens movie rating dataset: ml-latest-small.zip](https://grouplens.org/datasets/movielens/latest/).
An overview of this dataset is available [here]() but it's not required to read it to follow this tutorial.

@@@ note
This tutorial makes use of an AWS deployment of Blue Brain Nexus available at [Nexus AWS URL].
@@@

Let's get started.

## Set up Nexus CLI

* Install the Nexus CLI

```shell
$ (pip|brew) install nexus-cli
```

* Login with a token

```shell
$ nexus login [Nexus AWS URL] -t [TOKEN]
...
```

TBFD

## Create a project

Projects in BlueBrain Nexus are spaces where data can be **managed** ( **created**, **validated**, **secured**, **updated**, **deprecated**), **accessed** and **shared**.
A project is always created within an organization just like a git repository in a github organization.

@@@ note
A public **organization named "amld2019"** was created for the purpose of this tutorial. All projects will be created under the "amld2019" organization.
@@@

Let first select the amld2019 organization.

```shell
$ nexus organization amld2019
Set current organization to amld2019 on server [SERVER]
```

Create a project in the organization "amld2019 using your [USERNAME].

```shell
$ nexus create project [USERNAME]
Created project named "USERNAME" on organization "amld2019" and server "SERVER"
```

By default created projects are private. Only the project creator has read and write access. We'll see below how to make a project public.

The following command shows the list of projects you have access to. The project you just created should be the only one listed at this point.
Note the * character showing the current project you're working in.


```shell
$ nexus projects
Projects on server "SERVER":
    * USERNAME
```

The following command displays a short description of the current project.

```shell
 $ nexus project
 Working with project "USERNAME" on organization "amld2019" and server "SERVER"
```

A complete project description can also be displayed by adding the -v (for verbose) flag. A default configuration was generated as you can see.

```shell
 $ nexus project -v
 Working with project "USERNAME" on organization "amld2019" and server "SERVER".
 Project configuration:
    '{
      "@context": "https://bbp-nexus.epfl.ch/staging/v1/contexts/nexus/core/resource/v0.4.0",
      "@id": "https://bbp-nexus.epfl.ch/staging/v1/projects/anorg/USERNAME",
      "@type": "nxv:Project",
      "_uuid": "714839aa-8262-460a-8b90-dbfff2db7b5c",
      "base": "https://bbp-nexus.epfl.ch/staging/v1/resources/anorg/newtest/_/",
      "vocab": "ttps://bbp-nexus.epfl.ch/staging/v1/vocabs/anorg/newtest/",
      "label": "USERNAME",
      "name": "USERNAME",
      "prefixMappings": [],
      "_rev": 1,
      "_deprecated": false
    }'
```


## Create a dataset

We are all set to bring some data withing the project we just created. The next tabs show the three different flavours to bring data in a project.

From json payload
:   @@snip [resource.sh](../assets/create_from_json.sh)

From json file
:   @@snip [resource.json](../assets/create_json_from_file.sh)

From csv file
:   @@snip [resource-ref-new.json](../assets/create_csv_from_file.sh)




## Query a dataset

The created dataset can be accessed in three different ways:

list dataset
:   @@snip [resource.sh](../assets/list_dataset.sh)

full text search
:   @@snip [resource-ref-new.json](../assets/elasticsearch.sh)

navigate the graph
:   @@snip [resource.json](../assets/sparql.sh)


## Make a dataset public

Making a dataset public means granting read permissions to "anonymous" user.

```shell
$ nexus grant read on schemaid/resourceid to anonymous
```

To check that the dataset is now public, logout and list the resources within your project again. The dataset should be visible for non authenticated users.