package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews.{evaluate, next, ValidateProjection, ValidateSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.CrossProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewState._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.Json
import monix.bio.IO
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class CompositeViewsStmSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with CompositeViewsFixture {
  "A CompositeViews STM" when {

    val validSource: ValidateSource           = _ => IO.unit
    val invalidSource: ValidateSource         = {
      case s: CrossProjectSource => IO.raiseError(CrossProjectSourceProjectNotFound(s))
      case _                     => IO.unit
    }
    val validProjection: ValidateProjection   = _ => IO.unit
    val invalidProjection: ValidateProjection = {
      case _: ElasticSearchProjection => IO.raiseError(InvalidElasticSearchProjectionPayload(None))
      case _                          => IO.unit
    }

    def current(
        id: Iri = id,
        project: ProjectRef = projectRef,
        uuid: UUID = uuid,
        value: CompositeViewValue = viewValue,
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

    val eval = evaluate(validSource, validProjection, 2, 2)(_, _)

    "evaluating the CreateCompositeView command" should {
      val cmd = CreateCompositeView(id, project.ref, viewFields, source, subject, project.base)
      "emit an CompositeViewCreated " in {
        val expected = CompositeViewCreated(id, project.ref, uuid, viewValue, source, 1L, epoch, subject)
        eval(Initial, cmd).accepted shouldEqual expected
      }
      "raise a ViewAlreadyExists rejection" in {
        eval(current(), cmd).rejectedWith[ViewAlreadyExists]
      }
      "raise an InvalidElasticSearchProjectionPayload rejection" in {
        evaluate(validSource, invalidProjection, 2, 2)(Initial, cmd).rejectedWith[InvalidElasticSearchProjectionPayload]
      }
      "raise an InvalidSource rejection" in {
        evaluate(invalidSource, validProjection, 2, 2)(Initial, cmd).rejectedWith[CrossProjectSourceProjectNotFound]
      }

      "raise an TooManySources rejection" in {
        evaluate(validSource, validProjection, 1, 2)(Initial, cmd).rejectedWith[TooManySources]
      }

      "raise an TooManyProjections rejection" in {
        evaluate(validSource, validProjection, 2, 1)(Initial, cmd).rejectedWith[TooManyProjections]
      }
    }

    "evaluating the UpdateCompositeView command" should {
      val cmd = UpdateCompositeView(id, project.ref, 1L, updatedFields, source, subject, project.base)
      "emit an CompositeViewCreated " in {
        val expected = CompositeViewUpdated(id, project.ref, uuid, updatedValue, source, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "raise a IncorrectRev rejection" in {
        eval(current(), cmd.copy(rev = 2L)).rejectedWith[IncorrectRev]
      }
      "raise a ViewNotFound rejection" in {
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise an InvalidElasticSearchProjectionPayload rejection" in {
        evaluate(validSource, invalidProjection, 2, 2)(current(), cmd)
          .rejectedWith[InvalidElasticSearchProjectionPayload]
      }
      "raise an InvalidSource rejection" in {
        evaluate(invalidSource, validProjection, 2, 2)(current(), cmd).rejectedWith[CrossProjectSourceProjectNotFound]
      }
      "raise a ViewIsDeprecated rejection" in {
        eval(current(deprecated = true), cmd).rejectedWith[ViewIsDeprecated]
      }

      "raise an TooManySources rejection" in {
        evaluate(validSource, validProjection, 1, 2)(current(), cmd).rejectedWith[TooManySources]
      }

      "raise an TooManyProjections rejection" in {
        evaluate(validSource, validProjection, 2, 1)(current(), cmd).rejectedWith[TooManyProjections]
      }
    }

    "evaluating the TagCompositeView command" should {
      val tag = TagLabel.unsafe("tag")
      val cmd = TagCompositeView(id, project.ref, 1L, tag, 1L, subject)
      "emit an CompositeViewTagAdded" in {
        val expected = CompositeViewTagAdded(id, project.ref, uuid, 1L, tag, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        eval(current(), cmd.copy(rev = 2L)).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        eval(current(deprecated = true), cmd).rejectedWith[ViewIsDeprecated]
      }
      "raise a RevisionNotFound rejection for revisions higher that the current" in {
        eval(current(), cmd.copy(targetRev = 2L)).rejectedWith[RevisionNotFound]
      }
    }

    "evaluating the DeprecateCompositeView command" should {
      val cmd = DeprecateCompositeView(id, project.ref, 1L, subject)
      "emit an CompositeViewDeprecated" in {
        val expected = CompositeViewDeprecated(id, project.ref, uuid, 2L, epoch, subject)
        eval(current(), cmd).accepted shouldEqual expected
      }
      "raise a ViewNotFound rejection" in {
        eval(Initial, cmd).rejectedWith[ViewNotFound]
      }
      "raise a IncorrectRev rejection" in {
        eval(current(), cmd.copy(rev = 2L)).rejectedWith[IncorrectRev]
      }
      "raise a ViewIsDeprecated rejection" in {
        eval(current(deprecated = true), cmd).rejectedWith[ViewIsDeprecated]
      }
    }

    "applying an CompositeViewCreated event" should {
      "discard the event for a Current state" in {
        next(
          current(),
          CompositeViewCreated(id, project.ref, uuid, viewValue, source, 1L, epoch, subject)
        ) shouldEqual current()
      }
      "change the state" in {
        next(
          Initial,
          CompositeViewCreated(id, project.ref, uuid, viewValue, source, 1L, epoch, subject)
        ) shouldEqual current(value = viewValue, createdBy = subject, updatedBy = subject)
      }
    }

    "applying an CompositeViewUpdated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          CompositeViewUpdated(id, project.ref, uuid, viewValue, source, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          CompositeViewUpdated(id, project.ref, uuid, updatedValue, source2, 2L, epochPlus10, subject)
        ) shouldEqual current(
          value = updatedValue,
          source = source2,
          rev = 2L,
          updatedAt = epochPlus10,
          updatedBy = subject
        )
      }
    }

    "applying an CompositeViewTagAdded event" should {
      val tag = TagLabel.unsafe("tag")

      "discard the event for an Initial state" in {
        next(
          Initial,
          CompositeViewTagAdded(id, project.ref, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          CompositeViewTagAdded(id, project.ref, uuid, 1L, tag, 2L, epoch, subject)
        ) shouldEqual current(tags = Map(tag -> 1L), rev = 2L, updatedBy = subject)
      }

    }

    "applying an CompositeViewDeprecated event" should {
      "discard the event for an Initial state" in {
        next(
          Initial,
          CompositeViewDeprecated(id, project.ref, uuid, 2L, epoch, subject)
        ) shouldEqual Initial
      }
      "change the state" in {
        next(
          current(),
          CompositeViewDeprecated(id, project.ref, uuid, 2L, epoch, subject)
        ) shouldEqual current(deprecated = true, rev = 2L, updatedBy = subject)
      }
    }
  }
}
