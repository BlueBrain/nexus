package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, ResourceReference}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{ArchiveAlreadyExists, DuplicateResourcePath, PathIsNotAbsolute}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation.SourceJson
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{ArchiveCreated, ArchiveState, ArchiveValue, CreateArchive}
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
      project = Some(project),
      path = Some(Paths.get("/resource.json")),
      representation = Some(SourceJson)
    )
    val bob     = User("bob", Label.unsafe("realm"))
    "computing the next state" should {
      "accept the event information on an Initial state" in {
        val current = ArchiveState.Initial
        val event   = ArchiveCreated(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
          instant = Instant.EPOCH,
          subject = Anonymous
        )
        val state   = Current(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        Archives.next(current, event) shouldEqual state
      }
      "accept the event information on a Current state" in {
        val current = Current(
          id = iri"http://localhost/${genString()}",
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        val event   = ArchiveCreated(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
          instant = Instant.EPOCH,
          subject = bob
        )
        val state   = Current(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
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
          value = ArchiveValue(NonEmptySet.of(res)),
          subject = bob
        )
        val event   = ArchiveCreated(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
          instant = Instant.EPOCH,
          subject = bob
        )
        Archives.eval(Initial, command).accepted shouldEqual event
      }

      "reject creating an archive when the state is not Initial" in {
        val state   = Current(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
          createdAt = Instant.EPOCH,
          createdBy = Anonymous
        )
        val command = CreateArchive(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res)),
          subject = Anonymous
        )
        Archives.eval(state, command).rejectedWith[ArchiveAlreadyExists]
      }

      "reject creating an archive when a path is not absolute" in {
        val firstPath  = Paths.get("a/b")
        val first      = res.copy(path = Some(firstPath))
        val secondPath = Paths.get("c/d")
        val second     = res.copy(path = Some(secondPath))
        val command    = CreateArchive(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res, first, second)),
          subject = Anonymous
        )
        val rejection  = Archives.eval(Initial, command).rejectedWith[PathIsNotAbsolute]
        rejection shouldEqual PathIsNotAbsolute(Set(firstPath, secondPath))
      }

      "reject creating an archive when there are path collisions" in {
        val duplicatePath1 = Paths.get("/duplicate1")
        val duplicatePath2 = Paths.get("/duplicate2")

        val first  = res.copy(ref = Latest(iri"http://localhost/${genString()}"), path = Some(duplicatePath1))
        val second = res.copy(ref = Latest(iri"http://localhost/${genString()}"), path = Some(duplicatePath1))
        val third  = FileReference(Latest(iri"http://localhost/${genString()}"), Some(project), Some(duplicatePath2))
        val fourth = FileReference(Latest(iri"http://localhost/${genString()}"), Some(project), Some(duplicatePath2))

        val command   = CreateArchive(
          id = id,
          project = project,
          value = ArchiveValue(NonEmptySet.of(res, first, second, third, fourth)),
          subject = Anonymous
        )
        val rejection = Archives.eval(Initial, command).rejectedWith[DuplicateResourcePath]
        rejection shouldEqual DuplicateResourcePath(Set(duplicatePath1, duplicatePath2))
      }
    }
  }

}
