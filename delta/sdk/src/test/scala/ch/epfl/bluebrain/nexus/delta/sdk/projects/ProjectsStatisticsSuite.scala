package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsStatisticsSuite.{Cheese, Fruit}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import munit.AnyFixture

import java.time.Instant
import scala.annotation.nowarn

class ProjectsStatisticsSuite extends BioSuite with Doobie.Fixture with ConfigFixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val epoch = Instant.EPOCH

  private lazy val fruitStore = ScopedStateStore[String, Fruit](
    EntityType("fruit"),
    Fruit.serializer,
    queryConfig,
    xas
  )

  private lazy val cheeseStore = ScopedStateStore[String, Cheese](
    EntityType("cheese"),
    Cheese.serializer,
    queryConfig,
    xas
  )

  private lazy val stats = ProjectsStatistics(xas, cacheConfig).runSyncUnsafe()

  private val org  = Label.unsafe("org")
  private val org2 = Label.unsafe("org2")

  private val proj  = ProjectRef(org, Label.unsafe("proj"))
  private val proj2 = ProjectRef(org, Label.unsafe("proj2"))
  private val proj3 = ProjectRef(org2, Label.unsafe("proj3"))

  test("Insert some fruits and cheeses") {
    (
      fruitStore.save(Fruit(proj, "banana", 3, epoch)) >>
        fruitStore.save(Fruit(proj, "apple", 1, epoch.plusSeconds(10L))) >>
        fruitStore.save(Fruit(proj, "banana", 1, epoch), UserTag.unsafe("v1")) >>
        cheeseStore.save(Cheese(proj, "gruyere", 5, epoch.plusSeconds(15L))) >>
        fruitStore.save(Fruit(proj2, "pineapple", 3, epoch)) >>
        cheeseStore.save(Cheese(proj2, "morbier", 3, epoch))
    ).transact(xas.write).assert(())
  }

  test("Return the expected stats for proj1") {
    stats.get(proj).assertSome(ProjectStatistics(3L, 9L, epoch.plusSeconds(15L)))
  }

  test("Return the expected stats for proj2") {
    stats.get(proj2).assertSome(ProjectStatistics(2L, 6L, epoch))
  }

  test("Return noen for proj3") {
    stats.get(proj3).assertNone
  }
}

object ProjectsStatisticsSuite {

  final case class Fruit(project: ProjectRef, id: String, rev: Int, updatedAt: Instant) extends ScopedState {

    override def deprecated: Boolean = false

    override def createdAt: Instant = Instant.EPOCH

    override def createdBy: Identity.Subject = Anonymous

    override def updatedBy: Identity.Subject = Anonymous

    override def schema: ResourceRef = ResourceRef(iri"https://bluebrain.github.io/fruit")

    override def types: Set[IriOrBNode.Iri] = Set.empty
  }

  object Fruit {
    @nowarn("cat=unused")
    val serializer: Serializer[String, Fruit] = {
      implicit val configuration: Configuration = Serializer.circeConfiguration
      implicit val coder: Codec.AsObject[Fruit] = deriveConfiguredCodec[Fruit]
      Serializer(_.id)
    }
  }

  final case class Cheese(project: ProjectRef, id: String, rev: Int, updatedAt: Instant) extends ScopedState {

    override def deprecated: Boolean = false

    override def createdAt: Instant = Instant.EPOCH

    override def createdBy: Identity.Subject = Anonymous

    override def updatedBy: Identity.Subject = Anonymous

    override def schema: ResourceRef = ResourceRef(iri"https://bluebrain.github.io/cheese")

    override def types: Set[IriOrBNode.Iri] = Set.empty
  }

  object Cheese {
    @nowarn("cat=unused")
    val serializer: Serializer[String, Cheese] = {
      implicit val configuration: Configuration  = Serializer.circeConfiguration
      implicit val coder: Codec.AsObject[Cheese] = deriveConfiguredCodec[Cheese]
      Serializer(_.id)
    }
  }

}
