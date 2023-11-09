package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite

import java.time.Instant

class AnnotatedSourceSuite extends CatsEffectSuite with CirceLiteral {

  implicit private val cl: ClassLoader              = getClass.getClassLoader
  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      contexts.metadata -> ContextValue.fromFile("contexts/metadata.json")
    )

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val id       = nxv + "id"
  private val project  = ProjectRef.unsafe("org", "proj")
  private val resource = ResourceF(
    id,
    ResourceUris.resource(project, project, schemas.resources),
    5,
    Set(nxv + "Type"),
    deprecated = false,
    Instant.EPOCH,
    Anonymous,
    Instant.EPOCH,
    Anonymous,
    Latest(schemas.resources),
    ()
  )

  private def expected(expectedId: Iri) =
    json"""{
             "@context" : "https://bluebrain.github.io/nexus/contexts/metadata.json",
             "_constrainedBy" : "https://bluebrain.github.io/nexus/schemas/unconstrained.json",
             "_createdAt" : "1970-01-01T00:00:00Z",
             "_createdBy" : "http://localhost/v1/anonymous",
             "_deprecated" : false,
             "_incoming" : "http://localhost/v1/resources/org/proj/_/https:%2F%2Fbluebrain.github.io%2Fnexus%2Fschemas%2Funconstrained.json/incoming",
             "_outgoing" : "http://localhost/v1/resources/org/proj/_/https:%2F%2Fbluebrain.github.io%2Fnexus%2Fschemas%2Funconstrained.json/outgoing",
             "_project" : "http://localhost/v1/projects/org/proj",
             "_rev" : 5,
             "_schemaProject" : "http://localhost/v1/projects/org/proj",
             "_self" : "http://localhost/v1/resources/org/proj/_/https:%2F%2Fbluebrain.github.io%2Fnexus%2Fschemas%2Funconstrained.json",
             "_updatedAt" : "1970-01-01T00:00:00Z",
             "_updatedBy" : "http://localhost/v1/anonymous",
             "@id" : "$expectedId",
             "@type" : "https://bluebrain.github.io/nexus/vocabulary/Type",
             "source" : "original payload"
           }"""

  test("Merge metadata and source injecting the missing id from the source") {
    val source = json"""{"source": "original payload"}"""
    AnnotatedSource(resource, source).assertEquals(expected(id))
  }

  test("Merge metadata and source keeping the id from the source") {
    val sourceId = nxv + "sourceId"
    val source   = json"""{ "@id": "$sourceId", "source": "original payload"}"""
    AnnotatedSource(resource, source).assertEquals(expected(sourceId))
  }

}
