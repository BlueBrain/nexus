# Composite Sinks

A Composite Sink handles the following steps of composite view indexing

* Querying for the graphs of resources in the Blazegraph common namespace of the view
* Converting the obtained graphs into a format that can be pushed to a target sink
* Finally, pushing the resources to the target sink

These steps can be implemented in different ways. In Nexus Delta, there are two kinds of Composite Sink that can be
selected via configuration.

1. @ref:[Single Composite Sink](#single-composite-sink)
2. @ref:[Batch Composite Sink](#batch-composite-sink)

## Single Composite Sink

By default Nexus Delta will use the Single Composite Sink.

## Batch Composite Sink

@@@ note { .warning }

We recommend to start using Composite Views with the default @ref:[Single Composite Sink](#single-composite-sink). Once
you have a good understanding of it, the Batch Composite Sink can be used to enhance the performance of your deployment.

@@@

Starting with Delta 1.9, it is possible to configure Nexus Delta to use a Batch Composite Sink. This implementation of
the Composite Sink can query the Blazegraph common namespace for multiple resource IDs at the same time.

### Configuring the Batch Composite Sink

In order to enable the Batch Composite Sink, configure the following Nexus Delta property

`plugins.composite-views.sink-config = batch`

Furthermore, you can configure the maximum size of a batch and the maximum interval using

`plugins.composite-views.{plugin}-batch.max-elements = {max-elements}`

`plugins.composite-views.{plugin}-batch.max-interval = {max-interval}`

where:

* `{plugin}` is either `elasticsearch` or `blazegraph`. The batching options of a Composite Sink can be set separately for each target type.
* `{max-elements}` is the maximum number of elements to batch at once (defaults to `10`)
* `{max-interval}` is the maximum interval of time to wait for `{max-elements}` elements

### How to write a SPARQL construct query for the Batch Composite Sink

In order to use the Batch Composite Sink successfully, it is necessary to rework some aspects of a regular SPARQL
construct query. We explain the changes through an example.

### Example

Suppose we are in a situation where Composite Views are using the Single Composite Sink and have the following query

```sparql
PREFIX schema: <http://schema.org/>
PREFIX nxv: <https://bluebrain.github.io/nexus/vocabulary/>
CONSTRUCT {
  ?id     nxv:name   ?name   ;
          nxv:age    ?age    .
          nxv:parent ?parent .
} WHERE {
  BIND({resource_id} AS ?id) .
  ?id schema:name ?name .
  
  OPTIONAL { ?id schema:age ?age }
  OPTIONAL { ?id schema:parent ?parent . }
}
```

Using the default Single Composite Sink, Nexus Delta will query the resources Alice and Bob individually and obtain the
following n-triples from Blazegraph

```
<http://people.com/Alice> <http://schema.org/name> <Alice>
<http://people.com/Alice> <http://schema.org/parent> <http://people.com/Bob>
```

```
<http://people.com/Bob> <http://schema.org/name> <Bob>
<http://people.com/Bob> <http://schema.org/age> <42>
```

In particular, note that when looking at the graph of Alice, we do not know the age of Bob.

The first change to introduce in order to make this query work with batches of resources is to replace
the `BIND({resource_id} AS ?id) .` with `VALUES ?id { {resources_id} }`. Nexus Delta will use this template to
replace `{resource_id}` with multiple resource in case it receives a batch of more than one element. The query is now:

```sparql
PREFIX schema: <http://schema.org/>
PREFIX nxv: <https://bluebrain.github.io/nexus/vocabulary/>
CONSTRUCT {
  ?id     nxv:name   ?name   ;
          nxv:age    ?age    .
          nxv:parent ?parent .
} WHERE {
  VALUES ?id { {resource_id} } .
  ?id schema:name ?name .
  
  OPTIONAL { ?id schema:age ?age }
  OPTIONAL { ?id schema:parent ?parent . }
}
```

With the Batch Composite Sink enabled, if Alice and Bob are batched together, this query will result in the following
triples:

```
<http://people.com/Alice> <http://schema.org/name> <Alice>
<http://people.com/Alice> <http://schema.org/parent> <http://people.com/Bob>
<http://people.com/Bob> <http://schema.org/name> <Bob>
<http://people.com/Bob> <http://schema.org/age> <42>
```

Note how the results are the merged result of the individual queries. While we were able to query several resources
simultaneously, we are now facing a framing problem. If we try to frame `http://people.com/Alice`, its graph now
contains more information than before; it will now include the age of Bob, something that we did not request.

In order to solve this problem, we will introduce aliasing for the root resource IDs. The query will now become

```sparql
PREFIX schema: <http://schema.org/>
PREFIX nxv: <https://bluebrain.github.io/nexus/vocabulary/>
CONSTRUCT {
  ?alias  nxv:name   ?name   ;
          nxv:age    ?age    .
          nxv:parent ?parent .
} WHERE {
  VALUES ?id { {resource_id} } .
  BIND(IRI(CONCAT(STR(?id), '/alias')) AS ?alias) .
  
  ?id schema:name ?name .
  
  OPTIONAL { ?id schema:age ?age }
  OPTIONAL { ?id schema:parent ?parent }
}
```

With this query, a batch query for both Alice and Bob will now yield

```
<http://people.com/Alice/alias> <http://schema.org/name> <Alice>
<http://people.com/Alice/alias> <http://schema.org/parent> <http://people.com/Bob>
<http://people.com/Bob/alias> <http://schema.org/name> <Bob>
<http://people.com/Bob/alias> <http://schema.org/age> <42>
```

You can see that the root node of Bob's graph is now `http://people.com/Bob/alias`, while Alice's parent
is `http://people.com/Bob`. This distinction ensures that we cannot get Bob's age by looking at Alice's graph, thus
reproducing the behavior that we had with the Single Composite Sink.

Nexus Delta takes care of framing these results so that the framed documents will be the same as with the Single
Composite Sink, and will not contain any `alias` keyword. For example, for the resource `http://people.com/Alice`, Nexus
Delta will obtain its graph by looking at
the `http://people.com/Alice/alias` root node, and use the resulting graph (removing the `/alias` part)  for JSON-LD
framing.

#### Summary

To use the Batch Composite Sink the following changes are necessary:

* `BIND({resource_id} AS ?id)` must become `VALUES ?id { {resource_id} }` to allow for batches of resources to be
  queried from Blazegraph.
* `BIND(IRI(CONCAT(STR(?id), '/alias')) AS ?alias)` needs to be added, and the relevant (`?id`) "root nodes" replaced
  by `?alias` in the `CONSTRUCT` part of the query. This is done in order to avoid any clashes between the graphs of
  several resources. Do note that in case
  you have several "root nodes" in the `CONSTRUCT` part of your construct query, you might need several aliases.
