package ch.epfl.bluebrain.nexus.rdf.jena

import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.{rdf, schema, xml}
import org.apache.jena.rdf.model.{AnonId, Model, ModelFactory, Resource, ResourceFactory}
import org.apache.jena.rdf.model.impl.ResourceImpl

object Fixture {

  /*
        a - rdf:type    - schema:Person
        a - schema:name - A
        a - schema:age  - 16
        a - schema:age  - bnode
                          bnode - rdf:type        - schema:QuantitativeValue
                          bnode - schema:value    - 16
                          bnode - schema:unitText - years
   */
  val model: Model  = ModelFactory.createDefaultModel()
  val aUrl: Iri.Url = url"http://example.com/a"
  val a: Resource   = ResourceFactory.createResource(aUrl.asUri)
  model.add(
    a,
    ResourceFactory.createProperty(rdf.tpe.asUri),
    ResourceFactory.createResource(schema.Person.asUri)
  )
  model.add(
    a,
    ResourceFactory.createProperty(schema.name.asUri),
    ResourceFactory.createLangLiteral("A", "en")
  )
  model.add(
    a,
    ResourceFactory.createProperty(schema.age.asUri),
    ResourceFactory.createTypedLiteral(16)
  )
  val bnode = new ResourceImpl(AnonId.create("bnode"))
  model.add(
    a,
    ResourceFactory.createProperty(schema.age.asUri),
    bnode
  )
  model.add(
    bnode,
    ResourceFactory.createProperty(rdf.tpe.asUri),
    ResourceFactory.createResource(schema.QuantitativeValue.asUri)
  )
  model.add(
    bnode,
    ResourceFactory.createProperty(schema.value.asUri),
    ResourceFactory.createTypedLiteral(16)
  )
  model.add(
    bnode,
    ResourceFactory.createProperty(schema.unitText.asUri),
    ResourceFactory.createStringLiteral("years")
  )

  val graph: Graph = Graph(
    aUrl,
    Set(
      (aUrl, rdf.tpe, schema.Person),
      (aUrl, schema.name, Literal("A", LanguageTag("en").getOrElse(throw new IllegalArgumentException))),
      (aUrl, schema.age, Literal("16", xml.int)),
      (aUrl, schema.age, b"bnode"),
      (b"bnode", rdf.tpe, schema.QuantitativeValue),
      (b"bnode", schema.unitText, Literal("years")),
      (b"bnode", schema.value, Literal("16", xml.int))
    )
  )

}
