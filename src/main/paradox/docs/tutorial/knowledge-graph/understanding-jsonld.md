# Understanding JSON-LD

## RDF

**RDF (Resource Description Framework)** is a (extremely simple and truly **schemaless**) data model where **every piece of knowledge is broken down in (subject, predicate and object) called triple**.

![ds](./assets/triple.png)



## JSON-LD

[JSON-LD](https://json-ld.org/) (JSON for Linking Data) can be seen in two ways:


### More than a JSON format

A simple json example:

```json
[
  	 {
  		"name":"python",
  		"loadedFrom":"java"
  	 },
  	{
  		"name":"java",
  		"testedFrom":"java"
  	 }

  ]
```

What this data is about ? Programming languages or particular snake type and coffee you would find in the Java Indonesia ?
There is clearly a lack of context resulting in an ambiguity. JSON-LD allows to solve the ambiguity problem by enabling a semantic-preserving data exchange.
It does that by adding to a json document a @context object where every key is associated with an identifier.

For example with the payload below machine and human agents can look up the context to find out which JAVA are this data talking about.

```json
{
  [
  	 {
  	    "@context":"https://json-ld.org/contexts/programminglanguage.jsonld",
  		“name”:”python”,
  		“loadedFrom”:”java”
  	 },
  	{
  	    "@context":"https://json-ld.org/contexts/programminglanguage.jsonld",
  		“name”:”java”,
  		“testedFrom”:”java”
  	 }

  ]
}
```

Let go the [JSON-LD playground](https://json-ld.org/playground/) to have a closer look at its data model.
