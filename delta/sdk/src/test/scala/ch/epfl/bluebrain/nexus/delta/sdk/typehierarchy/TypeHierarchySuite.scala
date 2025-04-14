package ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchy.TypeHierarchyMapping
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchyRejection.{RevisionNotFound, TypeHierarchyAlreadyExists, TypeHierarchyDoesNotExist}
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, TypeHierarchyResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

import java.time.Instant

class TypeHierarchySuite extends NexusSuite with ConfigFixtures with FixedClock with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[?]] = List(doobieTruncateAfterTest)
  private lazy val xas                           = doobieTruncateAfterTest()

  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))

  private val config             = TypeHierarchyConfig(eventLogConfig)
  private lazy val typeHierarchy = TypeHierarchy.apply(xas, config, clock)

  private val mapping: TypeHierarchyMapping = Map(
    iri"https://schema.org/Movie" -> Set(iri"https://schema.org/CreativeWork", iri"https://schema.org/Thing")
  )
  private val updatedMapping                = mapping ++ Map(
    iri"https://schema.org/VideoGame" -> Set(iri"https://schema.org/SoftwareApplication", iri"https://schema.org/Thing")
  )

  test("Fetching a type hierarchy should fail when it doesn't exist") {
    typeHierarchy.fetch.intercept[TypeHierarchyDoesNotExist.type]
  }

  test("Creating a type hierarchy should succeed") {
    typeHierarchy.create(mapping) >>
      typeHierarchy.fetch.map(_.value.mapping).assertEquals(mapping)
  }

  test("Creating a type hierarchy should fail when it already exists") {
    typeHierarchy.create(mapping) >>
      typeHierarchy.create(mapping).intercept[TypeHierarchyAlreadyExists.type]
  }

  test("Fetching a type hierarchy at a specific revision should fail when it doesn't exist") {
    typeHierarchy.create(mapping) >>
      typeHierarchy.fetch(2).interceptEquals(RevisionNotFound(2, 1))
  }

  test("Updating a type hierarchy should succeed") {
    typeHierarchy.create(mapping) >>
      typeHierarchy.update(updatedMapping, 1) >>
      typeHierarchy.fetch(2).map(_.value.mapping).assertEquals(updatedMapping)
  }

  test("Creation should return the correct metadata") {
    typeHierarchy.create(mapping).map(assertMetadataIsCorrect(_, expectedRev = 1))
  }

  test("Updating should return the correct metadata") {
    typeHierarchy.create(mapping) >>
      typeHierarchy
        .update(updatedMapping, 1)
        .map(assertMetadataIsCorrect(_, expectedRev = 2))
  }

  test("Fetching should return the correct metadata") {
    typeHierarchy.create(mapping) >>
      typeHierarchy.fetch.map(assertMetadataIsCorrect(_, expectedRev = 1))
  }

  test("Fetching by revision should return the correct metadata") {
    typeHierarchy.create(mapping) >>
      typeHierarchy.fetch(1).map(assertMetadataIsCorrect(_, expectedRev = 1))
  }

  def assertMetadataIsCorrect(r: TypeHierarchyResource, expectedRev: Int) = {
    assertEquals(r.id, nxv + "TypeHierarchy")
    assertEquals(r.rev, expectedRev)
    assertEquals(r.types, Set(nxv.TypeHierarchy))
    assertEquals(r.deprecated, false)
    assertEquals(r.createdAt, Instant.EPOCH)
    assertEquals(r.createdBy, subject)
    assertEquals(r.updatedAt, Instant.EPOCH)
    assertEquals(r.updatedBy, subject)
  }

}
