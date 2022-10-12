package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewCommand.{CreateBlazegraphView, DeprecateBlazegraphView, TagBlazegraphView, UpdateBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent.{BlazegraphViewCreated, BlazegraphViewDeprecated, BlazegraphViewTagAdded, BlazegraphViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{DifferentBlazegraphViewType, IncorrectRev, InvalidViewReferences, PermissionIsNotDefined, ResourceAlreadyExists, RevisionNotFound, ViewIsDeprecated, ViewNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.{IndexingBlazegraphView => BlazegraphType}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewState, BlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import io.circe.Json
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class BlazegraphViewsStmSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with OptionValues
    with IOFixedClock
    with IOValues
    with Fixtures {

  "A Blazegraph STM" when {

    val uuid                  = UUID.randomUUID()
    implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

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

    def current(
        id: Iri = id,
        project: ProjectRef = project,
        uuid: UUID = uuid,
        value: BlazegraphViewValue = indexingValue,
        source: Json = source,
        tags: Tags = Tags.empty,
        rev: Int = 1,
        deprecated: Boolean = false,
        createdAt: Instant = epoch,
        createdBy: Subject = Anonymous,
        updatedAt: Instant = epoch,
        updatedBy: Subject = Anonymous
    ): BlazegraphViewState =
      BlazegraphViewState(
        id,
        project,
        uuid,
        value,
        source,
        tags,
        rev,
        deprecated,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy
      )

    val invalidView: ValidateBlazegraphView = {
      case v: AggregateBlazegraphViewValue => IO.raiseError(InvalidViewReferences(v.views.toSortedSet))
      case v: IndexingBlazegraphViewValue  => IO.raiseError(PermissionIsNotDefined(v.permission))
    }

    val eval = evaluate(alwaysValidate)(_, _)

    "evaluating the CreateBlazegraphView command" should {
      "emit an BlazegraphViewCreated for an IndexingBlazegraphViewValue" in {
        val cmd      = CreateBlazegraphView(id, project, indexingValue, source, subject)
        val expected = BlazegraphViewCreated(id, project, uuid, indexingValue, source, 1, epoch, subject)
        eval(None, cmd).accepted shouldEqual expected
      }
      "emit an BlazegraphViewCreated for an AggregateBlazegraphViewValue" in {
        val cmd      = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        val expected = BlazegraphViewCreated(id, project, uuid, aggregateValue, source, 1, epoch, subject)
        eval(None, cmd).accepted shouldEqual expected
      }
      "raise a ResourceAlreadyExists rejection when blazegraph view already exists" in {
        val cmd = CreateBlazegraphView(id, project, aggregateValue, source, subject)
        eval(Some(current()), cmd).rejectedWith[ResourceAlreadyExists]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = CreateBlazegraphView(id, project, indexingValue, source, subject)
        evaluate(invalidView)(None, cmd)
          .rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the UpdateBlazegraphView command" should {
      "emit an BlazegraphViewUpdated for an IndexingBlazegraphViewValue" in {
        val value    = indexingValue.copy(resourceTag = Some(UserTag.unsafe("sometag")))
        val cmd      = UpdateBlazegraphView(id, project, value, 1, source, subject)
        val expected = BlazegraphViewUpdated(id, project, uuid, value, source, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "emit an BlazegraphViewUpdated for an AggregateBlazegraphViewValue" in {
        val state    =
          current(value = aggregateValue.copy(views = NonEmptySet.of(ViewRef(project, iri"http://localhost/view"))))
        val cmd      = UpdateBlazegraphView(id, project, aggregateValue, 1, source, subject)
        val expected = BlazegraphViewUpdated(id, project, uuid, aggregateValue, source, 2, epoch, subject)
        eval(Some(state), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1, source, subject)
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 2, source, subject)
        eval(Some(current()), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1, source, subject)
        eval(Some(current(deprecated = true)), cmd).rejectedWith[ViewIsDeprecated]
      }
      "raise a DifferentBlazegraphViewType rejection" in {
        val cmd = UpdateBlazegraphView(id, project, aggregateValue, 1, source, subject)
        eval(Some(current()), cmd).rejectedWith[DifferentBlazegraphViewType]
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = UpdateBlazegraphView(id, project, aggregateValue, 1, source, subject)
        evaluate(invalidView)(Some(current(value = aggregateValue)), cmd).rejectedWith[InvalidViewReferences]
      }

      "raise a PermissionIsNotDefined rejection" in {
        val cmd = UpdateBlazegraphView(id, project, indexingValue, 1, source, subject)
        evaluate(invalidView)(Some(current()), cmd).rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the TagBlazegraphView command" should {
      val tag = UserTag.unsafe("tag")
      "emit an BlazegraphViewTagAdded" in {
        val cmd      = TagBlazegraphView(id, project, 1, tag, 1, subject)
        val expected = BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1, tag, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = TagBlazegraphView(id, project, 1, tag, 1, subject)
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = TagBlazegraphView(id, project, 1, tag, 2, subject)
        eval(Some(current()), cmd).rejectedWith[IncorrectRev]
      }
      "emit an BlazegraphViewTagAdded when view is deprecated" in {
        val cmd      = TagBlazegraphView(id, project, 1, tag, 1, subject)
        val expected = BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1, tag, 2, epoch, subject)
        eval(Some(current(deprecated = true)), cmd).accepted shouldEqual expected
      }
      "raise a RevisionNotFound rejection for negative revision values" in {
        val cmd = TagBlazegraphView(id, project, 0, tag, 1, subject)
        eval(Some(current()), cmd).rejectedWith[RevisionNotFound]
      }
      "raise a RevisionNotFound rejection for revisions higher that the current" in {
        val cmd = TagBlazegraphView(id, project, 2, tag, 1, subject)
        eval(Some(current()), cmd).rejectedWith[RevisionNotFound]
      }
    }

    "evaluating the DeprecateBlazegraphView command" should {
      "emit an BlazegraphViewDeprecated" in {
        val cmd      = DeprecateBlazegraphView(id, project, 1, subject)
        val expected = BlazegraphViewDeprecated(id, project, BlazegraphType, uuid, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 1, subject)
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 2, subject)
        eval(Some(current()), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = DeprecateBlazegraphView(id, project, 1, subject)
        eval(Some(current(deprecated = true)), cmd).rejectedWith[ViewIsDeprecated]
      }
    }

    "applying an BlazegraphViewCreated event" should {
      "discard the event for a Current state" in {
        next(
          Some(current()),
          BlazegraphViewCreated(id, project, uuid, indexingValue, source, 1, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          None,
          BlazegraphViewCreated(id, project, uuid, aggregateValue, source, 1, epoch, subject)
        ).value shouldEqual current(value = aggregateValue, createdBy = subject, updatedBy = subject)
      }
    }

    "applying an BlazegraphViewUpdated event" should {
      "discard the event for an Initial state" in {
        next(
          None,
          BlazegraphViewUpdated(id, project, uuid, indexingValue, source, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          BlazegraphViewUpdated(id, project, uuid, aggregateValue, source2, 2, epochPlus10, subject)
        ).value shouldEqual current(
          value = aggregateValue,
          source = source2,
          rev = 2,
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
    }

    "applying an BlazegraphViewTagAdded event" should {
      val tag = UserTag.unsafe("tag")
      "discard the event for an Initial state" in {
        next(
          None,
          BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1, tag, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          BlazegraphViewTagAdded(id, project, BlazegraphType, uuid, 1, tag, 2, epoch, subject)
        ).value shouldEqual current(tags = Tags(tag -> 1), rev = 2, updatedBy = subject)
      }
    }

    "applying an BlazegraphViewDeprecated event" should {
      "discard the event for an Initial state" in {
        next(
          None,
          BlazegraphViewDeprecated(id, project, BlazegraphType, uuid, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          BlazegraphViewDeprecated(id, project, BlazegraphType, uuid, 2, epoch, subject)
        ).value shouldEqual current(deprecated = true, rev = 2, updatedBy = subject)
      }
    }
  }
}
