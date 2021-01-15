package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewCommand.{CreateBlazegraphView, DeprecateBlazegraphView, TagBlazegraphView, UpdateBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.{BlazegraphViewCreated, BlazegraphViewDeprecated, BlazegraphViewTagAdded, BlazegraphViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{DifferentBlazegraphViewType, IncorrectRev, InvalidViewReference, PermissionIsNotDefined, RevisionNotFound, ViewAlreadyExists, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewValue, ViewRef}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class BlazegraphViewsStmSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers {

  "A Blazegraph STM" when {

    val uuid                   = UUID.randomUUID()
    implicit val uuidF: UUIDF  = UUIDF.fixed(uuid)
    implicit val sc: Scheduler = Scheduler.global

    val epoch       = Instant.EPOCH
    val epochPlus10 = Instant.EPOCH.plusMillis(10L)
    val subject     = User("myuser", Label.unsafe("myrealm"))
    val id          = iri"http://localhost/${UUID.randomUUID()}"
    val project     = ProjectRef(Label.unsafe("myorg"), Label.unsafe("myproj"))
    val viewRef     = ViewRef(project, iri"http://localhost/${UUID.randomUUID()}")
    val source      = Json.obj()
    val source2     = Json.obj("key" -> Json.fromInt(1))

    val indexingValue  = IndexingBlazegraphViewValue(
      Set.empty,
      Set.empty,
      None,
      includeMetadata = false,
      includeDeprecated = false,
      Permission.unsafe("my/permission")
    )
    val aggregateValue = AggregateBlazegraphViewValue(NonEmptySet.one(viewRef))

    val validPermission: Permission => IO[PermissionIsNotDefined, Unit]   = _ => IO.unit
    val invalidPermission: Permission => IO[PermissionIsNotDefined, Unit] =
      p => IO.raiseError(PermissionIsNotDefined(p))

    val validRef: ViewRef => IO[InvalidViewReference, Unit]   = _ => IO.unit
    val invalidRef: ViewRef => IO[InvalidViewReference, Unit] = ref => IO.raiseError(InvalidViewReference(ref))

    def current(
        id: Iri = id,
        project: ProjectRef = project,
        uuid: UUID = uuid,
        value: BlazegraphViewValue = indexingValue,
        source: Json = source,
        tags: Map[TagLabel, Long] = Map.empty,
        rev: Long = 1L,
        deprecated: Boolean = false,
        createdAt: Instant = epoch,
        createdBy: Subject = Anonymous,
        updatedAt: Instant = epoch,
        updatedBy: Subject = Anonymous
    ): Current =
      Current(id, project, uuid, value, source, tags, rev, deprecated, createdAt, createdBy, updatedAt, updatedBy)

    "evaluating the CreateBlazegraphView command" should {

      "emit an BlazegraphViewCreated for an IndexingBlazegraphViewValue" in {
        val cmd      = CreateBlazegraphView(id, project, indexingValue, source, subject)
        val expected = BlazegraphViewCreated(id, project, uuid, indexingValue, source, 1L, epoch, subject)
        evaluate(validPermission, validRef)(Initial, cmd).accepted shouldEqual expected
      }
      "emit an BlazegraphViewCreated for an AggregateBlazegraphViewValue" in {
        val cmd      = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        val expected = BlazegraphViewCreated(id, project, uuid, aggregateValue, source, 1L, epoch, subject)
        evaluate(validPermission, validRef)(Initial, cmd).accepted shouldEqual expected
      }
      "raise a ViewAlreadyExists rejection" in {
        val cmd = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        val st  = current()
        evaluate(validPermission, validRef)(st, cmd).rejectedWith[ViewAlreadyExists]
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        evaluate(validPermission, invalidRef)(Initial, cmd).rejectedWith[InvalidViewReference]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = CreateBlazegraphView(id, project, indexingValue, source, subject)
        evaluate(invalidPermission, validRef)(Initial, cmd).rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the UpdateBlazegraphView command" should {
      "emit an BlazegraphViewUpdated for an IndexingBlazegraphViewValue" in {
        val value    = indexingValue.copy(resourceTag = Some(TagLabel.unsafe("sometag")))
        val cmd      = UpdateBlazegraphView(id, project, value, 1L, source, subject)
        val expected = BlazegraphViewUpdated(id, project, uuid, value, source, 2L, epoch, subject)
        evaluate(validPermission, validRef)(current(), cmd).accepted shouldEqual expected
      }
      "emit an BlazegraphViewUpdated for an AggregateBlazegraphViewValue" in {
        val state    =
          current(value = aggregateValue.copy(views = NonEmptySet.one(ViewRef(project, iri"http://localhost/view"))))
        val cmd      = UpdateBlazegraphView(id, project, aggregateValue, 1L, source, subject)
        val expected = BlazegraphViewUpdated(id, project, uuid, aggregateValue, source, 2L, epoch, subject)
        evaluate(validPermission, validRef)(state, cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1L, source, subject)
        evaluate(validPermission, validRef)(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 2L, source, subject)
        evaluate(validPermission, validRef)(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1L, source, subject)
        evaluate(validPermission, validRef)(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
      "raise a DifferentBlazegraphViewType rejection" in {
        val cmd = UpdateBlazegraphView(id, project, aggregateValue, 1L, source, subject)
        evaluate(validPermission, validRef)(current(), cmd).rejectedWith[DifferentBlazegraphViewType]
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = UpdateBlazegraphView(id, project, aggregateValue, 1L, source, subject)
        evaluate(validPermission, invalidRef)(current(value = aggregateValue), cmd)
          .rejectedWith[InvalidViewReference]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1L, source, subject)
        evaluate(invalidPermission, validRef)(current(), cmd).rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the TagBlazegraphView command" should {
      val tag = TagLabel.unsafe("tag")
      "emit an BlazegraphViewTagAdded" in {
        val cmd      = TagBlazegraphView(id, project, 1L, tag, 1L, subject)
        val expected = BlazegraphViewTagAdded(id, project, uuid, 1L, tag, 2L, epoch, subject)
        evaluate(validPermission, validRef)(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = TagBlazegraphView(id, project, 1L, tag, 1L, subject)
        evaluate(validPermission, validRef)(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = TagBlazegraphView(id, project, 1L, tag, 2L, subject)
        evaluate(validPermission, validRef)(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = TagBlazegraphView(id, project, 1L, tag, 1L, subject)
        evaluate(validPermission, validRef)(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
      "raise a RevisionNotFound rejection for negative revision values" in {
        val cmd = TagBlazegraphView(id, project, 0L, tag, 1L, subject)
        evaluate(validPermission, validRef)(current(), cmd).rejectedWith[RevisionNotFound]
      }
      "raise a RevisionNotFound rejection for revisions higher that the current" in {
        val cmd = TagBlazegraphView(id, project, 2L, tag, 1L, subject)
        evaluate(validPermission, validRef)(current(), cmd).rejectedWith[RevisionNotFound]
      }
    }

    "evaluating the DeprecateBlazegraphView command" should {
      "emit an BlazegraphViewDeprecated" in {
        val cmd      = DeprecateBlazegraphView(id, project, 1L, subject)
        val expected = BlazegraphViewDeprecated(id, project, uuid, 2L, epoch, subject)
        evaluate(validPermission, validRef)(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 1L, subject)
        evaluate(validPermission, validRef)(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 2L, subject)
        evaluate(validPermission, validRef)(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 1L, subject)
        evaluate(validPermission, validRef)(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
    }

    "applying an BlazegraphViewCreated event" should {
      "discard the event for a Current state" in {
        next(
          current(),
          BlazegraphViewCreated(id, project, uuid, indexingValue, source, 1L, epoch, subject)
        ) shouldEqual current()
      }
      "change the state" in {
        next(
          Initial,
          BlazegraphViewCreated(id, project, uuid, aggregateValue, source, 1L, epoch, subject)
        ) shouldEqual current(value = aggregateValue, createdBy = subject, updatedBy = subject)
      }
    }

    "applying an BlazegraphViewUpdated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          BlazegraphViewUpdated(id, project, uuid, indexingValue, source, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          BlazegraphViewUpdated(id, project, uuid, aggregateValue, source2, 2L, epochPlus10, subject)
        ) shouldEqual current(
          value = aggregateValue,
          source = source2,
          rev = 2L,
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
    }

    "applying an BlazegraphViewTagAdded event" should {
      val tag = TagLabel.unsafe("tag")
      "discard the event for an Initial state" in {
        next(
          Initial,
          BlazegraphViewTagAdded(id, project, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          BlazegraphViewTagAdded(id, project, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual current(tags = Map(tag -> 1L), rev = 2L, updatedBy = subject)
      }
    }

    "applying an BlazegraphViewDeprecated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          BlazegraphViewDeprecated(id, project, uuid, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          BlazegraphViewDeprecated(id, project, uuid, 2L, epoch, subject)
        ) shouldEqual current(deprecated = true, rev = 2L, updatedBy = subject)
      }
    }

  }

}
