package ai.senscience.nexus.delta.plugins.graph.analytics.indexing

import ai.senscience.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsStreamSuite.Sample
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all.*
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import munit.AnyFixture

import java.time.Instant

class GraphAnalyticsStreamSuite extends NexusSuite with Doobie.Fixture with ConfigFixtures {

  override def munitFixtures: Seq[AnyFixture[?]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val sampleStore = ScopedStateStore[Iri, Sample](
    Sample.entityType,
    Sample.serializer,
    queryConfig,
    xas
  )

  test("Retrieve the types for associated to a collection of ids") {

    val project1         = ProjectRef.unsafe("org", "proj1")
    val sample1          = Sample(project1, nxv + "1", Set(nxv + "A", nxv + "B"))
    val sample2          = Sample(project1, nxv + "2", Set(nxv + "A"))
    val sample3          = Sample(project1, nxv + "3", Set(nxv + "B", nxv + "C"))
    val sample4          = Sample(project1, nxv + "4", Set(nxv + "D", nxv + "E"))
    val project1Samples  = List(sample1, sample2, sample3, sample4)
    val expectedProject1 = project1Samples.map { s => s.id -> s.types }.toMap

    val project2         = ProjectRef.unsafe("org", "proj2")
    val sample5          = Sample(project2, nxv + "5", Set(nxv + "F"))
    val project2Samples  = List(sample5)
    val expectedProject2 = project2Samples.map { s => s.id -> s.types }.toMap

    def findRelationships(project: ProjectRef, ids: Set[Iri]) =
      GraphAnalyticsStream.findRelationships(project, xas, 2)(ids)

    for {
      // Saving samples
      _ <- (project1Samples ++ project2Samples).traverse(sampleStore.save).transact(xas.write)
      // Asserting relationships
      _ <- findRelationships(project1, expectedProject1.keySet).assertEquals(expectedProject1)
      _ <- findRelationships(project2, expectedProject2.keySet).assertEquals(expectedProject2)
      _ <- findRelationships(project2, Set.empty).assertEquals(Map.empty[Iri, Set[Iri]])
    } yield ()

  }

}

object GraphAnalyticsStreamSuite {

  final private case class Sample(project: ProjectRef, id: Iri, types: Set[Iri]) extends ScopedState {

    override def rev: Int = 1

    override def deprecated: Boolean = false

    override def createdAt: Instant = Instant.EPOCH

    override def createdBy: Identity.Subject = Anonymous

    override def updatedAt: Instant = Instant.EPOCH

    override def updatedBy: Identity.Subject = Anonymous

    override def schema: ResourceRef = ResourceRef(Vocabulary.schemas.resources)
  }

  private object Sample {

    val entityType: EntityType = EntityType("sample")

    val serializer: Serializer[Iri, Sample] = {
      implicit val configuration: Configuration  = Serializer.circeConfiguration
      implicit val coder: Codec.AsObject[Sample] = deriveConfiguredCodec[Sample]
      Serializer()
    }
  }

}
