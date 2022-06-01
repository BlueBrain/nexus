package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.{evaluate, next, ViewRefResolution}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewCommand.{CreateBlazegraphView, DeprecateBlazegraphView, TagBlazegraphView, UpdateBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.{BlazegraphViewCreated, BlazegraphViewDeprecated, BlazegraphViewTagAdded, BlazegraphViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{DifferentBlazegraphViewType, IncorrectRev, InvalidViewReference, PermissionIsNotDefined, ResourceAlreadyExists, RevisionNotFound, TooManyViewReferences, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.{IndexingBlazegraphView => BlazegraphType}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.permissions
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck.IdAvailability
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
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
    val aggregateValue = AggregateBlazegraphViewValue(NonEmptySet.of(viewRef))

    val validPermission: Permission => IO[PermissionIsNotDefined, Unit]   = _ => IO.unit
    val invalidPermission: Permission => IO[PermissionIsNotDefined, Unit] =
      p => IO.raiseError(PermissionIsNotDefined(p))

    val allIdsAvailable: IdAvailability[ResourceAlreadyExists] = (_, _) => IO.unit
    val noIdsAvailable: IdAvailability[ResourceAlreadyExists]  = (p, id) => IO.raiseError(ResourceAlreadyExists(id, p))

    val validRef: ViewRef => IO[InvalidViewReference, Unit]   = _ => IO.unit
    val viewRefResolution: ViewRefResolution                  = _ =>
      IO.pure(
        Set(
          IndexedVisitedView(ViewRef(project, nxv + "a"), permissions.read, ""),
          IndexedVisitedView(ViewRef(project, nxv + "b"), permissions.read, ""),
          AggregatedVisitedView(ViewRef(project, nxv + "c"), NonEmptySet.of(ViewRef(project, nxv + "a")))
        )
      )
    val invalidRef: ViewRef => IO[InvalidViewReference, Unit] = ref => IO.raiseError(InvalidViewReference(ref))

    def current(
        id: Iri = id,
        project: ProjectRef = project,
        uuid: UUID = uuid,
        value: BlazegraphViewValue = indexingValue,
        source: Json = source,
        tags: Map[UserTag, Long] = Map.empty,
        rev: Long = 1L,
        deprecated: Boolean = false,
        createdAt: Instant = epoch,
        createdBy: Subject = Anonymous,
        updatedAt: Instant = epoch,
        updatedBy: Subject = Anonymous
    ): Current =
      Current(id, project, uuid, value, source, tags, rev, deprecated, createdAt, createdBy, updatedAt, updatedBy)

    val eval = evaluate(validPermission, validRef, viewRefResolution, allIdsAvailable, 10)(_, _)

    "evaluating the CreateBlazegraphView command" should {
      "emit an BlazegraphViewCreated for an IndexingBlazegraphViewValue" in {
        val cmd      = CreateBlazegraphView(id, project, indexingValue, source, subject)
        val expected = BlazegraphViewCreated(id, project, uuid, indexingValue, source, 1L, epoch, subject)
        eval(Initial, cmd).accepted shouldEqual expected
      }
      "emit an BlazegraphViewCreated for an AggregateBlazegraphViewValue" in {
        val cmd      = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        val expected = BlazegraphViewCreated(id, project, uuid, aggregateValue, source, 1L, epoch, subject)
        eval(Initial, cmd).accepted shouldEqual expected
      }
      "raise a ResourceAlreadyExists rejection when blazegraph view already exists" in {
        val cmd = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        val st  = current()
        eval(st, cmd).rejectedWith[ResourceAlreadyExists]
      }
      "raise a ResourceAlreadyExists rejection" in {
        val cmd  = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        val eval = evaluate(validPermission, validRef, viewRefResolution, noIdsAvailable, 10)(_, _)
        eval(Initial, cmd).rejected shouldEqual ResourceAlreadyExists(cmd.id, cmd.project)
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        evaluate(validPermission, invalidRef, viewRefResolution, allIdsAvailable, 10)(Initial, cmd)
          .rejectedWith[InvalidViewReference]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = CreateBlazegraphView(id, project, indexingValue, source, subject)
        evaluate(invalidPermission, validRef, viewRefResolution, allIdsAvailable, 10)(Initial, cmd)
          .rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the UpdateBlazegraphView command" should {
      "emit an BlazegraphViewUpdated for an IndexingBlazegraphViewValue" in {
        val value    = indexingValue.copy(resourceTag = Some(UserTag.unsafe("sometag")))
        val cmd      = UpdateBlazegraphView(id, project, value, 1L, source, subject)
        val expected = BlazegraphViewUpdated(id, project, uuid, value, source, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "emit an BlazegraphViewUpdated for an AggregateBlazegraphViewValue" in {
        val state    =
          current(value = aggregateValue.copy(views = NonEmptySet.of(ViewRef(project, iri"http://localhost/view"))))
        val cmd      = UpdateBlazegraphView(id, project, aggregateValue, 1L, source, subject)
        val expected = BlazegraphViewUpdated(id, project, uuid, aggregateValue, source, 2L, epoch, subject)
        eval(state, cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1L, source, subject)
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 2L, source, subject)
        eval(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1L, source, subject)
        eval(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
      "raise a DifferentBlazegraphViewType rejection" in {
        val cmd = UpdateBlazegraphView(id, project, aggregateValue, 1L, source, subject)
        eval(current(), cmd).rejectedWith[DifferentBlazegraphViewType]
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = UpdateBlazegraphView(id, project, aggregateValue, 1L, source, subject)
        evaluate(validPermission, invalidRef, viewRefResolution, allIdsAvailable, 10)(
          current(value = aggregateValue),
          cmd
        )
          .rejectedWith[InvalidViewReference]
      }
      "raise a TooManyViewReferences rejection" in {
        val cmd = UpdateBlazegraphView(id, project, aggregateValue, 1L, source, subject)
        evaluate(validPermission, validRef, viewRefResolution, allIdsAvailable, 1)(current(value = aggregateValue), cmd)
          .rejectedWith[TooManyViewReferences]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1L, source, subject)
        evaluate(invalidPermission, validRef, viewRefResolution, allIdsAvailable, 10)(current(), cmd)
          .rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the TagBlazegraphView command" should {
      val tag = UserTag.unsafe("tag")
      "emit an BlazegraphViewTagAdded" in {
        val cmd      = TagBlazegraphView(id, project, 1L, tag, 1L, subject)
        val expected = BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1L, tag, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = TagBlazegraphView(id, project, 1L, tag, 1L, subject)
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = TagBlazegraphView(id, project, 1L, tag, 2L, subject)
        eval(current(), cmd).rejectedWith[IncorrectRev]
      }
      "emit an BlazegraphViewTagAdded when view is deprecated" in {
        val cmd      = TagBlazegraphView(id, project, 1L, tag, 1L, subject)
        val expected = BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1L, tag, 2L, epoch, subject)
        eval(current(deprecated = true), cmd).accepted shouldEqual expected
      }
      "raise a RevisionNotFound rejection for negative revision values" in {
        val cmd = TagBlazegraphView(id, project, 0L, tag, 1L, subject)
        eval(current(), cmd).rejectedWith[RevisionNotFound]
      }
      "raise a RevisionNotFound rejection for revisions higher that the current" in {
        val cmd = TagBlazegraphView(id, project, 2L, tag, 1L, subject)
        eval(current(), cmd).rejectedWith[RevisionNotFound]
      }
    }

    "evaluating the DeprecateBlazegraphView command" should {
      "emit an BlazegraphViewDeprecated" in {
        val cmd      = DeprecateBlazegraphView(id, project, 1L, subject)
        val expected = BlazegraphViewDeprecated(id, project, BlazegraphType, uuid, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 1L, subject)
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 2L, subject)
        eval(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 1L, subject)
        eval(current(deprecated = true), cmd)
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
      val tag = UserTag.unsafe("tag")
      "discard the event for an Initial state" in {
        next(
          Initial,
          BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual current(tags = Map(tag -> 1L), rev = 2L, updatedBy = subject)
      }
    }

    "applying an BlazegraphViewDeprecated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          BlazegraphViewDeprecated(id, project, BlazegraphType, uuid, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          BlazegraphViewDeprecated(id, project, BlazegraphType, uuid, 2L, epoch, subject)
        ) shouldEqual current(deprecated = true, rev = 2L, updatedBy = subject)
      }
    }

  }

}
