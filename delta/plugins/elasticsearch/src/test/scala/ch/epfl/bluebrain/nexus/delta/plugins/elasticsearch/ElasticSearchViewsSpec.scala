package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewValue, ViewRef}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
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

class ElasticSearchViewsSpec
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
    val indexingValue = IndexingElasticSearchViewValue(Set.empty, Set.empty, None, sourceAsText = false, includeMetadata = false, includeDeprecated = false, Json.obj(), Permission.unsafe("my/permission"))
    val aggregateValue = AggregateElasticSearchViewValue(NonEmptySet.one(viewRef))
    // format: on

    val validPermission: Permission => IO[PermissionIsNotDefined, Unit]   = _ => IO.unit
    val invalidPermission: Permission => IO[PermissionIsNotDefined, Unit] =
      p => IO.raiseError(PermissionIsNotDefined(p))

    val validMapping: Json => IO[InvalidElasticSearchMapping, Unit]   = _ => IO.unit
    val invalidMapping: Json => IO[InvalidElasticSearchMapping, Unit] =
      _ => IO.raiseError(InvalidElasticSearchMapping())

    val validRef: ViewRef => IO[InvalidViewReference, Unit]   = _ => IO.unit
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

    "evaluating the CreateElasticSearchView command" should {
      "emit an ElasticSearchViewCreated for an IndexingElasticSearchViewValue" in {
        val cmd      = CreateElasticSearchView(id, project, indexingValue, source, subject)
        val expected = ElasticSearchViewCreated(id, project, uuid, indexingValue, source, 1L, epoch, subject)
        evaluate(validPermission, validMapping, validRef)(Initial, cmd).accepted shouldEqual expected
      }
      "emit an ElasticSearchViewCreated for an AggregateElasticSearchViewValue" in {
        val cmd      = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        val expected = ElasticSearchViewCreated(id, project, uuid, aggregateValue, source, 1L, epoch, subject)
        evaluate(validPermission, validMapping, validRef)(Initial, cmd).accepted shouldEqual expected
      }
      "raise a ViewAlreadyExists rejection" in {
        val cmd = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        val st  = current()
        evaluate(validPermission, validMapping, validRef)(st, cmd).rejectedWith[ViewAlreadyExists]
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        evaluate(validPermission, validMapping, invalidRef)(Initial, cmd).rejectedWith[InvalidViewReference]
      }
      "raise an InvalidElasticSearchMapping rejection" in {
        val cmd = CreateElasticSearchView(id, project, indexingValue, source, subject)
        evaluate(validPermission, invalidMapping, validRef)(Initial, cmd).rejectedWith[InvalidElasticSearchMapping]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = CreateElasticSearchView(id, project, indexingValue, source, subject)
        evaluate(invalidPermission, validMapping, validRef)(Initial, cmd).rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the UpdateElasticSearchView command" should {
      "emit an ElasticSearchViewUpdated for an IndexingElasticSearchViewValue" in {
        val value    = indexingValue.copy(resourceTag = Some(TagLabel.unsafe("sometag")))
        val cmd      = UpdateElasticSearchView(id, project, value, 1L, source, subject)
        val expected = ElasticSearchViewUpdated(id, project, uuid, value, source, 2L, epoch, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).accepted shouldEqual expected
      }
      "emit an ElasticSearchViewUpdated for an AggregateElasticSearchViewValue" in {
        val state    =
          current(value = aggregateValue.copy(views = NonEmptySet.one(ViewRef(project, iri"http://localhost/view"))))
        val cmd      = UpdateElasticSearchView(id, project, aggregateValue, 1L, source, subject)
        val expected = ElasticSearchViewUpdated(id, project, uuid, aggregateValue, source, 2L, epoch, subject)
        evaluate(validPermission, validMapping, validRef)(state, cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = UpdateElasticSearchView(id, project, indexingValue, 1L, source, subject)
        evaluate(validPermission, validMapping, validRef)(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = UpdateElasticSearchView(id, project, indexingValue, 2L, source, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = UpdateElasticSearchView(id, project, indexingValue, 1L, source, subject)
        evaluate(validPermission, validMapping, validRef)(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
      "raise a DifferentElasticSearchViewType rejection" in {
        val cmd = UpdateElasticSearchView(id, project, aggregateValue, 1L, source, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).rejectedWith[DifferentElasticSearchViewType]
      }
      "raise an InvalidViewReference rejection" in {
        val cmd = UpdateElasticSearchView(id, project, aggregateValue, 1L, source, subject)
        evaluate(validPermission, validMapping, invalidRef)(current(value = aggregateValue), cmd)
          .rejectedWith[InvalidViewReference]
      }
      "raise an InvalidElasticSearchMapping rejection" in {
        val cmd = UpdateElasticSearchView(id, project, indexingValue, 1L, source, subject)
        evaluate(validPermission, invalidMapping, validRef)(current(), cmd).rejectedWith[InvalidElasticSearchMapping]
      }
      "raise a PermissionIsNotDefined rejection" in {
        val cmd = UpdateElasticSearchView(id, project, indexingValue, 1L, source, subject)
        evaluate(invalidPermission, validMapping, validRef)(current(), cmd).rejectedWith[PermissionIsNotDefined]
      }
    }

    "evaluating the TagElasticSearchView command" should {
      val tag = TagLabel.unsafe("tag")
      "emit an ElasticSearchViewTagAdded" in {
        val cmd      = TagElasticSearchView(id, project, 1L, tag, 1L, subject)
        val expected = ElasticSearchViewTagAdded(id, project, uuid, 1L, tag, 2L, epoch, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = TagElasticSearchView(id, project, 1L, tag, 1L, subject)
        evaluate(validPermission, validMapping, validRef)(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = TagElasticSearchView(id, project, 1L, tag, 2L, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = TagElasticSearchView(id, project, 1L, tag, 1L, subject)
        evaluate(validPermission, validMapping, validRef)(current(deprecated = true), cmd)
          .rejectedWith[ViewIsDeprecated]
      }
      "raise a RevisionNotFound rejection for negative revision values" in {
        val cmd = TagElasticSearchView(id, project, 0L, tag, 1L, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).rejectedWith[RevisionNotFound]
      }
      "raise a RevisionNotFound rejection for revisions higher that the current" in {
        val cmd = TagElasticSearchView(id, project, 2L, tag, 1L, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).rejectedWith[RevisionNotFound]
      }
    }

    "evaluating the DeprecateElasticSearchView command" should {
      "emit an ElasticSearchViewDeprecated" in {
        val cmd      = DeprecateElasticSearchView(id, project, 1L, subject)
        val expected = ElasticSearchViewDeprecated(id, project, uuid, 2L, epoch, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 1L, subject)
        evaluate(validPermission, validMapping, validRef)(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 2L, subject)
        evaluate(validPermission, validMapping, validRef)(current(), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 1L, subject)
        evaluate(validPermission, validMapping, validRef)(current(deprecated = true), cmd)
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
          ElasticSearchViewTagAdded(id, project, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          ElasticSearchViewTagAdded(id, project, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual current(tags = Map(tag -> 1L), rev = 2L, updatedBy = subject)
      }
    }

    "applying an ElasticSearchViewDeprecated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          ElasticSearchViewDeprecated(id, project, uuid, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          ElasticSearchViewDeprecated(id, project, uuid, 2L, epoch, subject)
        ) shouldEqual current(deprecated = true, rev = 2L, updatedBy = subject)
      }
    }

  }

}
