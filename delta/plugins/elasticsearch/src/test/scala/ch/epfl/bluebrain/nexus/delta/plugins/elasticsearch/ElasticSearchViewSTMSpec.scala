package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptySet
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.{ElasticSearch => ElasticSearchType}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewState, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json

import java.time.Instant
import java.util.UUID

class ElasticSearchViewSTMSpec extends CatsEffectSpec {

  "An ElasticSearch STM" when {

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

    // format: off
    val indexingValue            =
      IndexingElasticSearchViewValue(None, List(), None, None, None, Permission.unsafe("my/permission"))
    val indexingValueWithUserTag = indexingValue.copy(resourceTag = Some(UserTag.unsafe("tag")))
    val indexingValueWithName    = indexingValue.copy(name = Some("viewName"))
    val aggregateValue           = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef))
    // format: on

    val invalidView: ValidateElasticSearchView =
      (_: UUID, _: IndexingRev, _: ElasticSearchViewValue) => IO.raiseError(InvalidElasticSearchIndexPayload(None))

    def current(
        id: Iri = id,
        project: ProjectRef = project,
        uuid: UUID = uuid,
        value: ElasticSearchViewValue = indexingValue,
        source: Json = source,
        tags: Tags = Tags.empty,
        rev: Int = 1,
        indexingRev: IndexingRev = IndexingRev.init,
        deprecated: Boolean = false,
        createdAt: Instant = epoch,
        createdBy: Subject = Anonymous,
        updatedAt: Instant = epoch,
        updatedBy: Subject = Anonymous
    ): ElasticSearchViewState =
      ElasticSearchViewState(
        id,
        project,
        uuid,
        value,
        source,
        tags,
        rev,
        indexingRev,
        deprecated,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy
      )

    val eval = evaluate(ValidateElasticSearchView.always, clock)(_, _)

    "evaluating the CreateElasticSearchView command" should {
      "emit an ElasticSearchViewCreated for an IndexingElasticSearchViewValue" in {
        val cmd      = CreateElasticSearchView(id, project, indexingValue, source, subject)
        val expected = ElasticSearchViewCreated(id, project, uuid, indexingValue, source, 1, epoch, subject)
        eval(None, cmd).accepted shouldEqual expected
      }
      "emit an ElasticSearchViewCreated for an AggregateElasticSearchViewValue" in {
        val cmd      = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        val expected = ElasticSearchViewCreated(id, project, uuid, aggregateValue, source, 1, epoch, subject)
        eval(None, cmd).accepted shouldEqual expected
      }
      "raise a ResourceAlreadyExists rejection when elasticsearch view does not exists" in {
        val cmd = CreateElasticSearchView(id, project, aggregateValue, source, subject)
        val st  = current()
        eval(Some(st), cmd).rejectedWith[ResourceAlreadyExists]
      }

      "raise an InvalidElasticSearchMapping rejection" in {
        val cmd = CreateElasticSearchView(id, project, indexingValue, source, subject)
        evaluate(invalidView, clock)(None, cmd).rejectedWith[InvalidElasticSearchIndexPayload]
      }
    }

    "evaluating the UpdateElasticSearchView command" should {
      "emit an ElasticSearchViewUpdated for an IndexingElasticSearchViewValue" in {
        val value    = indexingValue.copy(resourceTag = Some(UserTag.unsafe("sometag")))
        val cmd      = UpdateElasticSearchView(id, project, 1, value, source, subject)
        val expected = ElasticSearchViewUpdated(id, project, uuid, value, source, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "emit an ElasticSearchViewUpdated for an AggregateElasticSearchViewValue" in {
        val state    =
          current(value = aggregateValue.copy(views = NonEmptySet.of(ViewRef(project, iri"http://localhost/view"))))
        val cmd      = UpdateElasticSearchView(id, project, 1, aggregateValue, source, subject)
        val expected = ElasticSearchViewUpdated(id, project, uuid, aggregateValue, source, 2, epoch, subject)
        eval(Some(state), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1, indexingValue, source, subject)
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 2, indexingValue, source, subject)
        eval(Some(current()), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1, indexingValue, source, subject)
        eval(Some(current(deprecated = true)), cmd).rejectedWith[ViewIsDeprecated]
      }
      "raise a DifferentElasticSearchViewType rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1, aggregateValue, source, subject)
        eval(Some(current()), cmd).rejectedWith[DifferentElasticSearchViewType]
      }
      "raise an InvalidElasticSearchMapping rejection" in {
        val cmd = UpdateElasticSearchView(id, project, 1, indexingValue, source, subject)
        evaluate(invalidView, clock)(Some(current()), cmd).rejectedWith[InvalidElasticSearchIndexPayload]
      }
    }

    "evaluating the TagElasticSearchView command" should {
      val tag = UserTag.unsafe("tag")
      "emit an ElasticSearchViewTagAdded" in {
        val cmd      = TagElasticSearchView(id, project, 1, tag, 1, subject)
        val expected = ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1, tag, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = TagElasticSearchView(id, project, 1, tag, 1, subject)
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = TagElasticSearchView(id, project, 1, tag, 2, subject)
        eval(Some(current()), cmd).rejectedWith[IncorrectRev]
      }
      "tag a deprecated view" in {
        val cmd      = TagElasticSearchView(id, project, 1, tag, 1, subject)
        val expected = ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1, tag, 2, epoch, subject)
        eval(Some(current(deprecated = true)), cmd).accepted shouldEqual expected
      }
      "raise a RevisionNotFound rejection for negative revision values" in {
        val cmd = TagElasticSearchView(id, project, 0, tag, 1, subject)
        eval(Some(current()), cmd).rejectedWith[RevisionNotFound]
      }
      "raise a RevisionNotFound rejection for revisions higher that the current" in {
        val cmd = TagElasticSearchView(id, project, 2, tag, 1, subject)
        eval(Some(current()), cmd).rejectedWith[RevisionNotFound]
      }
    }

    "evaluating the DeprecateElasticSearchView command" should {
      "emit an ElasticSearchViewDeprecated" in {
        val cmd      = DeprecateElasticSearchView(id, project, 1, subject)
        val expected = ElasticSearchViewDeprecated(id, project, ElasticSearchType, uuid, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 1, subject)
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 2, subject)
        eval(Some(current()), cmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        val cmd = DeprecateElasticSearchView(id, project, 1, subject)
        eval(Some(current(deprecated = true)), cmd).rejectedWith[ViewIsDeprecated]
      }
    }

    "evaluating the UndeprecateElasticSearchView command" should {
      "emit an ElasticSearchViewUndeprecated" in {
        val deprecatedState   = Some(current(deprecated = true))
        val undeprecateCmd    = UndeprecateElasticSearchView(id, project, 1, subject)
        val undeprecatedEvent = ElasticSearchViewUndeprecated(id, project, ElasticSearchType, uuid, 2, epoch, subject)
        eval(deprecatedState, undeprecateCmd).accepted shouldEqual undeprecatedEvent
      }
      "raise a ViewNotFound rejection" in {
        val undeprecateCmd = UndeprecateElasticSearchView(id, project, 1, subject)
        eval(None, undeprecateCmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val deprecatedState = Some(current(deprecated = true))
        val undeprecateCmd  = UndeprecateElasticSearchView(id, project, 2, subject)
        eval(deprecatedState, undeprecateCmd).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsNotDeprecated rejection" in {
        val activeView     = Some(current())
        val undeprecateCmd = UndeprecateElasticSearchView(id, project, 1, subject)
        eval(activeView, undeprecateCmd).rejectedWith[ViewIsNotDeprecated]
      }
    }

    "applying an ElasticSearchViewCreated event" should {
      "discard the event for a Current state" in {
        next(
          Some(current()),
          ElasticSearchViewCreated(id, project, uuid, indexingValue, source, 1, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          None,
          ElasticSearchViewCreated(id, project, uuid, aggregateValue, source, 1, epoch, subject)
        ).value shouldEqual current(value = aggregateValue, createdBy = subject, updatedBy = subject)
      }
    }

    "applying an ElasticSearchViewUpdated event" should {
      "discard the event for an Initial state" in {
        next(
          None,
          ElasticSearchViewUpdated(id, project, uuid, indexingValue, source, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state (aggregate view)" in {
        next(
          Some(current()),
          ElasticSearchViewUpdated(id, project, uuid, aggregateValue, source2, 2, epochPlus10, subject)
        ).value shouldEqual current(
          value = aggregateValue,
          source = source2,
          rev = 2,
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
      "change the state (indexing view, indexing revision)" in {
        next(
          Some(current()),
          ElasticSearchViewUpdated(id, project, uuid, indexingValueWithUserTag, source2, 2, epochPlus10, subject)
        ).value shouldEqual current(
          value = indexingValueWithUserTag,
          source = source2,
          rev = 2,
          indexingRev = IndexingRev(2),
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
      "change the state (indexing view, non-indexing revision)" in {
        next(
          Some(current()),
          ElasticSearchViewUpdated(id, project, uuid, indexingValueWithName, source2, 2, epochPlus10, subject)
        ).value shouldEqual current(
          value = indexingValueWithName,
          source = source2,
          rev = 2,
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
    }

    "applying an ElasticSearchViewTagAdded event" should {
      val tag = UserTag.unsafe("tag")
      "discard the event for an Initial state" in {
        next(
          None,
          ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1, tag, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          ElasticSearchViewTagAdded(id, project, ElasticSearchType, uuid, 1, tag, 2, epoch, subject)
        ).value shouldEqual current(tags = Tags(tag -> 1), rev = 2, updatedBy = subject)
      }
    }

    "applying an ElasticSearchViewDeprecated event" should {
      "discard the event for an Initial state" in {
        next(
          None,
          ElasticSearchViewDeprecated(id, project, ElasticSearchType, uuid, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          ElasticSearchViewDeprecated(id, project, ElasticSearchType, uuid, 2, epoch, subject)
        ).value shouldEqual current(deprecated = true, rev = 2, updatedBy = subject)
      }
    }

    "applying an ElasticSearchViewUndeprecated event" should {
      "discard the event for an Initial state" in {
        val undeprecatedEvent = ElasticSearchViewUndeprecated(id, project, ElasticSearchType, uuid, 2, epoch, subject)
        next(None, undeprecatedEvent) shouldEqual None
      }
      "change the state" in {
        val deprecatedState   = Some(current(deprecated = true))
        val undeprecatedEvent = ElasticSearchViewUndeprecated(id, project, ElasticSearchType, uuid, 2, epoch, subject)
        next(deprecatedState, undeprecatedEvent).value shouldEqual
          current(deprecated = false, rev = 2, updatedBy = subject)
      }
    }

  }

}
