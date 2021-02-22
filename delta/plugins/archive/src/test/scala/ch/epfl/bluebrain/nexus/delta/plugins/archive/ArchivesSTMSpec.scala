package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{ArchiveAlreadyExists, DuplicateResourcePath, PathIsNotAbsolute}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation.SourceJson
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{ArchiveCreated, ArchiveState, CreateArchive}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import java.time.Instant

class ArchivesSTMSpec extends AnyWordSpecLike with Matchers with IOValues with IOFixedClock with TestHelpers {

  "An Archive STM" when {
    val id      = iri"http://localhost${genString()}"
    val project = ProjectRef(Label.unsafe("org"), Label.unsafe("proj"))
    val res     = ResourceReference(
      ref = ResourceRef.Latest(id),
      project = project,
      path = Paths.get("/resource.json"),
      representation = SourceJson
    )
    val bob     = User("bob", Label.unsafe("realm"))
    "computing the next state" should {
      "accept the event information on an Initial state" in {
        val current = ArchiveState.Initial
        val event   = ArchiveCreated(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          instant = Instant.EPOCH,
          subject = Anonymous
        )
        val state   = Current(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        Archives.next(current, event) shouldEqual state
      }
      "accept the event information on a Current state" in {
        val current = Current(
          id = iri"http://localhost/${genString()}",
          project = project,
          resources = NonEmptySet.of(res),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        val event   = ArchiveCreated(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          instant = Instant.EPOCH,
          subject = bob
        )
        val state   = Current(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          createdAt = Instant.EPOCH,
          createdBy = bob
        )
        Archives.next(current, event) shouldEqual state
      }
    }

    "evaluating a command" should {

      "create a new archive" in {
        val command = CreateArchive(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          subject = bob
        )
        val event   = ArchiveCreated(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          instant = Instant.EPOCH,
          subject = bob
        )
        Archives.eval(Initial, command).accepted shouldEqual event
      }

      "reject creating an archive when the state is not Initial" in {
        val state   = Current(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        val command = CreateArchive(
          id = id,
          project = project,
          resources = NonEmptySet.of(res),
          subject = Anonymous
        )
        Archives.eval(state, command).rejectedWith[ArchiveAlreadyExists]
      }

      "reject creating an archive when a path is not absolute" in {
        val first     = res.copy(path = Paths.get("a/b"))
        val second    = res.copy(path = Paths.get("c/d"))
        val command   = CreateArchive(
          id = id,
          project = project,
          resources = NonEmptySet.of(res, first, second),
          subject = Anonymous
        )
        val rejection = Archives.eval(Initial, command).rejectedWith[PathIsNotAbsolute]
        rejection shouldEqual PathIsNotAbsolute(Set(first.path, second.path))
      }

      "reject creating an archive when there are path collisions" in {
        val first     = res.copy(ref = Latest(iri"http://localhost/${genString()}"), path = Paths.get("/duplicate"))
        val second    = res.copy(ref = Latest(iri"http://localhost/${genString()}"), path = Paths.get("/duplicate"))
        val third     = FileReference(Latest(iri"http://localhost/${genString()}"), project, Paths.get("/duplicate"))
        val command   = CreateArchive(
          id = id,
          project = project,
          resources = NonEmptySet.of(res, first, second, third),
          subject = Anonymous
        )
        val rejection = Archives.eval(Initial, command).rejectedWith[DuplicateResourcePath]
        rejection shouldEqual DuplicateResourcePath(Set(first.path, second.path, third.path))
      }
    }
  }

}
