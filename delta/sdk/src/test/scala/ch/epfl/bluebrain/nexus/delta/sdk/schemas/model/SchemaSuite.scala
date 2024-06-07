package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class SchemaSuite extends NexusSuite with Fixtures {

  private  val project                = ProjectRef.unsafe("org", "proj")

  test("Extract as a graph the content of the schema, removing the duplicates") {
    for {
      entitySource <- loader.jsonContentOf("schemas/entity.json")
      entityExpanded  <- ExpandedJsonLd(jsonContentOf("schemas/entity-expanded.json"))
      entityExpandedGraphSize <- IO.fromEither(entityExpanded.toGraph.map(_.getDefaultGraphSize))
      entityCompacted <- entityExpanded.toCompacted(entitySource.topContextValueOrEmpty)
      licenseExpanded <- ExpandedJsonLd(jsonContentOf("schemas/license-expanded.json"))
      licenseExpandedGraphSize <- IO.fromEither(licenseExpanded.toGraph.map(_.getDefaultGraphSize))
    } yield {
      val id = iri"https://neuroshapes.org/commons/entity"
      val expandeds = NonEmptyList.of(entityExpanded, licenseExpanded, entityExpanded)
      val schema = Schema(id, project, Tags.empty, entitySource, entityCompacted, expandeds)
      assertEquals(schema.shapes.getDefaultGraphSize , entityExpandedGraphSize + licenseExpandedGraphSize)
    }
  }

}
