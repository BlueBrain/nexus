package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tags}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class SchemaSuite extends NexusSuite with Fixtures {

  private val project = ProjectRef.unsafe("org", "proj")

  test("Extract as a graph the content of the schema, removing the duplicates") {
    for {
      entitySource             <- loader.jsonContentOf("schemas/entity.json")
      entityExpanded           <- ExpandedJsonLd(jsonContentOf("schemas/entity-expanded.json"))
      entityExpandedGraphSize  <- entityExpanded.toGraph.map(_.getDefaultGraphSize)
      entityCompacted          <- entityExpanded.toCompacted(entitySource.topContextValueOrEmpty)
      licenseExpanded          <- ExpandedJsonLd(jsonContentOf("schemas/license-expanded.json"))
      licenseExpandedGraphSize <- licenseExpanded.toGraph.map(_.getDefaultGraphSize)
      id                        = iri"https://neuroshapes.org/commons/entity"
      expandeds                 = NonEmptyList.of(entityExpanded, licenseExpanded, entityExpanded)
      schema                    = Schema(id, project, Tags.empty, entitySource, entityCompacted, expandeds)
      shapes                   <- schema.shapes
    } yield {
      assertEquals(shapes.getDefaultGraphSize, entityExpandedGraphSize + licenseExpandedGraphSize)
    }
  }

}
