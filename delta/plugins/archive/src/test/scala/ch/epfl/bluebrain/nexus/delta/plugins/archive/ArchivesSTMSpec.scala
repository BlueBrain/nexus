package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.ResourceReference
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{ArchiveAlreadyExists, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation.SourceJson
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{ArchiveCreated, ArchiveState, ArchiveValue, CreateArchive}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import java.time.Instant

class ArchivesSTMSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with EitherValuable
    with TestHelpers {

  "An Archive STM" when {
    val id      = iri"http://localhost${genString()}"
    val project = ProjectRef(Label.unsafe("org"), Label.unsafe("proj"))
    val res     = ResourceReference(
      ref = ResourceRef.Latest(id),
      project = Some(project),
      path = Some(AbsolutePath(Paths.get("/resource.json")).rightValue),
      representation = Some(SourceJson)
    )
    val bob     = User("bob", Label.unsafe("realm"))
    "computing the next state" should {
      "accept the event information on an Initial state" in {
        val current = ArchiveState.Initial
        val event   = ArchiveCreated(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          instant = Instant.EPOCH,
          subject = Anonymous
        )
        val state   = Current(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        Archives.next(current, event) shouldEqual state
      }
      "accept the event information on a Current state" in {
        val current = Current(
          id = iri"http://localhost/${genString()}",
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        val event   = ArchiveCreated(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          instant = Instant.EPOCH,
          subject = bob
        )
        val state   = Current(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          createdAt = Instant.EPOCH,
          createdBy = bob
        )
        Archives.next(current, event) shouldEqual state
      }
    }

    "evaluating a command" should {
      val allIdsAvailable: IdAvailability[ResourceAlreadyExists] = (_, _) => IO.unit
      val noIdsAvailable: IdAvailability[ResourceAlreadyExists]  = (p, id) => IO.raiseError(ResourceAlreadyExists(id, p))
      val eval                                                   = Archives.evaluate(allIdsAvailable)(_, _)
      "create a new archive" in {
        val command = CreateArchive(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          subject = bob
        )
        val event   = ArchiveCreated(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          instant = Instant.EPOCH,
          subject = bob
        )
        eval(Initial, command).accepted shouldEqual event
      }

      "reject with ResourceAlreadyExists" in {
        val command = CreateArchive(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          subject = bob
        )
        Archives.evaluate(noIdsAvailable)(Initial, command).rejected shouldEqual
          ResourceAlreadyExists(command.id, command.project)
      }

      "reject creating an archive when the state is not Initial" in {
        val state   = Current(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        val command = CreateArchive(
          id = id,
          project = project,
          value = ArchiveValue.unsafe(NonEmptySet.of(res)),
          subject = Anonymous
        )
        eval(state, command).rejectedWith[ArchiveAlreadyExists]
      }
    }
  }

}
