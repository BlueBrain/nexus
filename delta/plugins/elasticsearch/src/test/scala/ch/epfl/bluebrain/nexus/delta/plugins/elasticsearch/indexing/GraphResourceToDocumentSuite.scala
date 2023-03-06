package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.bio.JsonAssertions
import io.circe.Json
import munit.FunSuite

import java.time.Instant

class GraphResourceToDocumentSuite extends FunSuite with Fixtures with JsonAssertions {

  private val entityType = EntityType("entityType")
  private val project    = ProjectRef.unsafe("org", "project")
  private val id         = iri"http://nexus.example.com/6A518B91-7B12-451B-8E85-48C67432C3A1"
  private val rev        = 1
  private val deprecated = false
  private val schema     = ResourceRef(iri"http://schema.org/Person")
  private val types      = Set(iri"http://schema.org/Resource")

  private val expandedJson =
    json"""
      [
        {
          "@id": "http://nexus.example.com/6A518B91-7B12-451B-8E85-48C67432C3A1",
          "@type": "http://schema.org/Person",
          "http://schema.org/name": {"@value": "John Doe"}
        }
      ]
        """

  private val expanded      = ExpandedJsonLd.expanded(expandedJson).rightValue
  private val graph         = Graph(expanded).rightValue
  private val metadataGraph = graph

  implicit private val cl: ClassLoader = getClass.getClassLoader
  private val context                  = ContextValue.fromFile("/contexts/elasticsearch-indexing.json").accepted

  private val graphResourceToDocument = new GraphResourceToDocument(context, false)

  test("If the source has an expanded Iri `@id` it should be used") {
    val source =
      json"""
        {
          "@id": "http://nexus.example.com/john-doe",
          "@type": "http://schema.org/Person",
          "name": "John Doe"
        }
          """
    val elem   = elemFromSource(source)

    val expectedJson =
      json"""
        {
          "@id" : "http://nexus.example.com/john-doe",
          "@type" : "http://schema.org/Person",
          "name" : "John Doe"
        }
          """

    for {
      json <- graphResourceToDocument(elem).accepted.toOption
    } yield json.equalsIgnoreArrayOrder(expectedJson)
  }

  test("If the source has a compacted Iri `@id` it should be used") {
    val source =
      json"""
        {
          "@id": "nxv:JohnDoe",
          "@type": "http://schema.org/Person",
          "name": "John Doe"
        }
          """
    val elem   = elemFromSource(source)

    val expectedJson =
      json"""
        {
          "@id" : "nxv:JohnDoe",
          "@type" : "http://schema.org/Person",
          "name" : "John Doe"
        }
          """

    for {
      json <- graphResourceToDocument(elem).accepted.toOption
    } yield json.equalsIgnoreArrayOrder(expectedJson)
  }

  test("If the source does not have an `@id` it should be injected") {
    val source =
      json"""
        {
          "@type": "http://schema.org/Person",
          "name": "John Doe"
        }
      """
    val elem   = elemFromSource(source)

    val expectedJson =
      json"""
        {
          "@id" : "http://nexus.example.com/6A518B91-7B12-451B-8E85-48C67432C3A1",
          "@type" : "http://schema.org/Person",
          "name" : "John Doe"
        }
      """

    for {
      json <- graphResourceToDocument(elem).accepted.toOption
    } yield json.equalsIgnoreArrayOrder(expectedJson)
  }

  private def elemFromSource(source: Json): SuccessElem[GraphResource] = {
    val graphResource = GraphResource(
      entityType,
      project,
      id,
      rev,
      deprecated,
      schema,
      types,
      graph,
      metadataGraph,
      source
    )
    SuccessElem(entityType, id, Some(project), Instant.EPOCH, Offset.start, graphResource, 1)
  }

}
