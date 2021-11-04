package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{evaluate, next, ValidateIndex, ViewRefResolution}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.{ElasticSearch => ElasticSearchType}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class ElasticSearchViewSTMSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers {

  "An ElasticSearch STM" when {

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

    // format: off
    val indexingValue = IndexingElasticSearchViewValue(None, List(), None, None, None, Permission.unsafe("my/permission"))
    val aggregateValue = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef))
    // format: on

    val validPermission: Permission => IO[PermissionIsNotDefined, Unit]   = _ => IO.unit
    val invalidPermission: Permission => IO[PermissionIsNotDefined, Unit] =
      p => IO.raiseError(PermissionIsNotDefined(p))

    val validIndex: ValidateIndex   = (_, _) => IO.unit
    val invalidIndex: ValidateIndex = (_, _) => IO.raiseError(InvalidElasticSearchIndexPayload(None))

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
        value: ElasticSearchViewValue = indexingValue,
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

    val eval = evaluate(validPermission, validIndex, validRef, viewRefResolution, (_, _) => IO.unit, "prefix", 10)(_, _)

    "evaluating the CreateElasticSearchView command" should {
      "emit an ElasticSearchViewCreated for an IndexingElasticSearchViewValue" in {
        val cmd      = CreateElasticSearchView(id, project, indexingValue, source, subject)
        val expected = ElasticSearchViewCreated(id, project, uuid, indexingValue, source, 1L, epoch, subject)
        eval(Initial, cmd).accepted shouldEqual expected
      }
      "emit an ElasticSearchViewCreated for an AggregateElasticSearchViewValue" in {
        val cmd      = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        val expected = ElasticSearchViewCreated(id, project, uuid, aggregateValue, source, 1L, epoch, subject)
        eval(Initial, cmd).accepted shouldEqual expected
      }
      "raise a ResourceAlreadyExists rejection when elasticsearch view does not exists" in {
        val cmd = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        val st  = current()
        eval(st, cmd).rejectedWith[ResourceAlreadyExists]
      }
      "raise a ResourceAlreadyExists rejection" in {
        val eval = evaluate(
          validPermission,
          validIndex,
          validRef,
          viewRefResolution,
          (project, id) => IO.raiseError(ResourceAlreadyExists(id, project)),
          "prefix",
          10
        )(_, _)

        val cmd = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        eval(Initial, cmd).rejected shouldEqual ResourceAlreadyExists(cmd.id, cmd.project)
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        evaluate(validPermission, validIndex, invalidRef, viewRefResolution, (_, _) => IO.unit, "prefix", 10)(
          Initial,
          cmd
        )
          .rejectedWith[InvalidViewReference]
      }
      "raise a TooManyViewReferences rejection" in {
        val cmd = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        evaluate(validPermission, validIndex, validRef, viewRefResolution, (_, _) => IO.unit, "prefix", 1)(Initial, cmd)
          .rejectedWith[TooManyViewReferences]
      }
      "raise an InvalidElasticSearchMapping rejection" in {
        val cmd = CreateElasticSearchView(id, project, indexingValue, source, subject)
        evaluate(validPermission, invalidIndex, validRef, viewRefResolution, (_, _) => IO.unit, "prefix", 10)(
          Initial,
          cmd
        )
          .rejectedWith[InvalidElasticSearchIndexPayload]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = CreateElasticSearchView(id, project, indexingValue, source, subject)
        evaluate(invalidPermission, validIndex, validRef, viewRefResolution, (_, _) => IO.unit, "prefix", 10)(
          Initial,
          cmd
        )
          .rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the UpdateElasticSearchView command" should {
      "emit an ElasticSearchViewUpdated for an IndexingElasticSearchViewValue" in {
        val value    = indexingValue.copy(resourceTag = Some(TagLabel.unsafe("sometag")))
        val cmd      = UpdateElasticSearchView(id, project, 1L, value, source, subject)
        val expected = ElasticSearchViewUpdated(id, project, uuid, value, source, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "emit an ElasticSearchViewUpdated for an AggregateElasticSearchViewValue" in {
        val state    =
          current(value = aggregateValue.copy(views = NonEmptySet.of(ViewRef(project, iri"http://localhost/view"))))
        val cmd      = UpdateElasticSearchView(id, project, 1L, aggregateValue, source, subject)
        val expected = ElasticSearchViewUpdated(id, project, uuid, aggregateValue, source, 2L, epoch, subject)
        eval(state, cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1L, indexingValue, source, subject)
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 2L, indexingValue, source, subject)
        eval(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1L, indexingValue, source, subject)
        eval(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
      "raise a DifferentElasticSearchViewType rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1L, aggregateValue, source, subject)
        eval(current(), cmd).rejectedWith[DifferentElasticSearchViewType]
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1L, aggregateValue, source, subject)
        evaluate(validPermission, validIndex, invalidRef, viewRefResolution, (_, _) => IO.unit, "prefix", 10)(
          current(value = aggregateValue),
          cmd
        )
          .rejectedWith[InvalidViewReference]
      }
      "raise an InvalidElasticSearchMapping rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1L, indexingValue, source, subject)
        evaluate(validPermission, invalidIndex, validRef, viewRefResolution, (_, _) => IO.unit, "prefix", 10)(
          current(),
          cmd
        )
          .rejectedWith[InvalidElasticSearchIndexPayload]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1L, indexingValue, source, subject)
        evaluate(invalidPermission, validIndex, validRef, viewRefResolution, (_, _) => IO.unit, "prefix", 10)(
          current(),
          cmd
        )
          .rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the TagElasticSearchView command" should {
      val tag = TagLabel.unsafe("tag")
      "emit an ElasticSearchViewTagAdded" in {
        val cmd      = TagElasticSearchView(id, project, 1L, tag, 1L, subject)
        val expected = ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1L, tag, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = TagElasticSearchView(id, project, 1L, tag, 1L, subject)
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = TagElasticSearchView(id, project, 1L, tag, 2L, subject)
        eval(current(), cmd).rejectedWith[IncorrectRev]
      }
      "tag a deprecated view" in {
        val cmd      = TagElasticSearchView(id, project, 1L, tag, 1L, subject)
        val expected = ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1L, tag, 2L, epoch, subject)
        eval(current(deprecated = true), cmd).accepted shouldEqual expected

      }
      "raise a RevisionNotFound rejection for negative revision values" in {
        val cmd = TagElasticSearchView(id, project, 0L, tag, 1L, subject)
        eval(current(), cmd).rejectedWith[RevisionNotFound]
      }
      "raise a RevisionNotFound rejection for revisions higher that the current" in {
        val cmd = TagElasticSearchView(id, project, 2L, tag, 1L, subject)
        eval(current(), cmd).rejectedWith[RevisionNotFound]
      }
    }

    "evaluating the DeprecateElasticSearchView command" should {
      "emit an ElasticSearchViewDeprecated" in {
        val cmd      = DeprecateElasticSearchView(id, project, 1L, subject)
        val expected = ElasticSearchViewDeprecated(id, project, ElasticSearchType, uuid, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 1L, subject)
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 2L, subject)
        eval(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 1L, subject)
        eval(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
    }

    "applying an ElasticSearchViewCreated event" should {
      "discard the event for a Current state" in {
        next(
          current(),
          ElasticSearchViewCreated(id, project, uuid, indexingValue, source, 1L, epoch, subject)
        ) shouldEqual current()
      }
      "change the state" in {
        next(
          Initial,
          ElasticSearchViewCreated(id, project, uuid, aggregateValue, source, 1L, epoch, subject)
        ) shouldEqual current(value = aggregateValue, createdBy = subject, updatedBy = subject)
      }
    }

    "applying an ElasticSearchViewUpdated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          ElasticSearchViewUpdated(id, project, uuid, indexingValue, source, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          ElasticSearchViewUpdated(id, project, uuid, aggregateValue, source2, 2L, epochPlus10, subject)
        ) shouldEqual current(
          value = aggregateValue,
          source = source2,
          rev = 2L,
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
    }

    "applying an ElasticSearchViewTagAdded event" should {
      val tag = TagLabel.unsafe("tag")
      "discard the event for an Initial state" in {
        next(
          Initial,
          ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual current(tags = Map(tag -> 1L), rev = 2L, updatedBy = subject)
      }
    }

    "applying an ElasticSearchViewDeprecated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          ElasticSearchViewDeprecated(id, project, ElasticSearchType, uuid, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          ElasticSearchViewDeprecated(id, project, ElasticSearchType, uuid, 2L, epoch, subject)
        ) shouldEqual current(deprecated = true, rev = 2L, updatedBy = subject)
      }
    }

  }

}
