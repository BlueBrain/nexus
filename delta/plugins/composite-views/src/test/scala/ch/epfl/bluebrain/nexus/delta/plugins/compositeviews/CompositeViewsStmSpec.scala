package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewState, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json

import java.time.Instant
import java.util.UUID

class CompositeViewsStmSpec extends CatsEffectSpec with CompositeViewsFixture {
  "A CompositeViews STM" when {

    val validView: ValidateCompositeView   = (_, _) => IO.unit
    val invalidView: ValidateCompositeView = (_, _) => IO.raiseError(InvalidElasticSearchProjectionPayload(None))

    def current(
        id: Iri = id,
        project: ProjectRef = projectRef,
        uuid: UUID = uuid,
        value: CompositeViewValue = viewValue,
        source: Json = source,
        tags: Tags = Tags.empty,
        rev: Int = 1,
        deprecated: Boolean = false,
        createdAt: Instant = epoch,
        createdBy: Subject = Anonymous,
        updatedAt: Instant = epoch,
        updatedBy: Subject = Anonymous
    ): CompositeViewState =
      CompositeViewState(
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

    val eval = evaluate(validView, clock)(_, _)

    "evaluating the CreateCompositeView command" should {
      val cmd = CreateCompositeView(id, project.ref, viewFields, source, subject, project.base)
      "emit an CompositeViewCreated " in {
        val expected = CompositeViewCreated(id, project.ref, uuid, viewValue, source, 1, epoch, subject)
        eval(None, cmd).accepted shouldEqual expected
      }
      "raise a ViewAlreadyExists rejection" in {
        eval(Some(current()), cmd).rejectedWith[ViewAlreadyExists]
      }
      "raise an InvalidElasticSearchProjectionPayload rejection" in {
        evaluate(invalidView, clock)(None, cmd).rejectedWith[InvalidElasticSearchProjectionPayload]
      }
    }

    "evaluating the UpdateCompositeView command" should {
      val cmd = UpdateCompositeView(id, project.ref, 1, updatedFields, source, subject, project.base)
      "emit an CompositeViewCreated " in {
        val expected = CompositeViewUpdated(id, project.ref, uuid, updatedValue, source, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "raise a IncorrectRev rejection" in {
        eval(Some(current()), cmd.copy(rev = 2)).rejectedWith[IncorrectRev]
      }
      "raise a ViewNotFound rejection" in {
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise an InvalidElasticSearchProjectionPayload rejection" in {
        evaluate(invalidView, clock)(Some(current()), cmd).rejectedWith[InvalidElasticSearchProjectionPayload]
      }
      "raise a ViewIsDeprecated rejection" in {
        eval(Some(current(deprecated = true)), cmd).rejectedWith[ViewIsDeprecated]
      }
    }

    "evaluating the DeprecateCompositeView command" should {
      val cmd = DeprecateCompositeView(id, project.ref, 1, subject)
      "emit an CompositeViewDeprecated" in {
        val expected = CompositeViewDeprecated(id, project.ref, uuid, 2, epoch, subject)
        eval(Some(current()), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        eval(None, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        eval(Some(current()), cmd.copy(rev = 2)).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        eval(Some(current(deprecated = true)), cmd).rejectedWith[ViewIsDeprecated]
      }
    }

    "evaluating the UndeprecateCompositeView command" should {
      val undeprecateCmd = UndeprecateCompositeView(id, project.ref, 1, subject)
      "emit an CompositeViewUndeprecated" in {
        val deprecatedState   = Some(current(deprecated = true))
        val undeprecatedEvent = CompositeViewUndeprecated(id, project.ref, uuid, 2, epoch, subject)
        eval(deprecatedState, undeprecateCmd).accepted shouldEqual undeprecatedEvent
      }
      "raise a ViewNotFound rejection" in {
        eval(None, undeprecateCmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        val deprecatedState = Some(current(deprecated = true))
        eval(deprecatedState, undeprecateCmd.copy(rev = 2)).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsNotDeprecated rejection" in {
        eval(Some(current()), undeprecateCmd).rejectedWith[ViewIsNotDeprecated]
      }
    }

    "applying an CompositeViewCreated event" should {
      "discard the event for a Current state" in {
        next(
          Some(current()),
          CompositeViewCreated(id, project.ref, uuid, viewValue, source, 1, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          None,
          CompositeViewCreated(id, project.ref, uuid, viewValue, source, 1, epoch, subject)
        ).value shouldEqual current(value = viewValue, createdBy = subject, updatedBy = subject)
      }
    }

    "applying an CompositeViewUpdated event" should {
      "discard the event for an None state" in {
        next(
          None,
          CompositeViewUpdated(id, project.ref, uuid, viewValue, source, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          CompositeViewUpdated(id, project.ref, uuid, updatedValue, source2, 2, epochPlus10, subject)
        ).value shouldEqual current(
          value = updatedValue,
          source = source2,
          rev = 2,
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
    }

    "applying an CompositeViewTagAdded event" should {
      val tag = UserTag.unsafe("tag")

      "discard the event for an None state" in {
        next(
          None,
          CompositeViewTagAdded(id, project.ref, uuid, 1, tag, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          CompositeViewTagAdded(id, project.ref, uuid, 1, tag, 2, epoch, subject)
        ).value shouldEqual current(tags = Tags(tag -> 1), rev = 2, updatedBy = subject)
      }

    }

    "applying an CompositeViewDeprecated event" should {
      "discard the event for an None state" in {
        next(
          None,
          CompositeViewDeprecated(id, project.ref, uuid, 2, epoch, subject)
        ) shouldEqual None
      }
      "change the state" in {
        next(
          Some(current()),
          CompositeViewDeprecated(id, project.ref, uuid, 2, epoch, subject)
        ).value shouldEqual current(deprecated = true, rev = 2, updatedBy = subject)
      }
    }

    "applying an CompositeViewUndeprecated event" should {
      "discard the event for an None state" in {
        val undeprecatedEvent = CompositeViewUndeprecated(id, project.ref, uuid, 2, epoch, subject)
        next(None, undeprecatedEvent) shouldEqual None
      }
      "change the state" in {
        val deprecatedState   = Some(current(deprecated = true))
        val undeprecatedEvent = CompositeViewUndeprecated(id, project.ref, uuid, 2, epoch, subject)
        next(deprecatedState, undeprecatedEvent).value shouldEqual
          current(deprecated = false, rev = 2, updatedBy = subject)
      }
    }
  }
}
