package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestCommand.{Boom, Create, Merge, Never, Tag, Update}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestMerged, PullRequestTagged, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestRejection.{AlreadyExists, NotFound, PullRequestAlreadyClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestActive, PullRequestClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import monix.bio.IO

import java.time.Instant
import scala.annotation.nowarn

object PullRequest {

  val entityType: EntityType = EntityType("merge-request")

  val stateMachine: StateMachine[PullRequestState, PullRequestCommand, PullRequestEvent, PullRequestRejection] =
    StateMachine(
      None,
      (state: Option[PullRequestState], command: PullRequestCommand) =>
        state.fold[IO[PullRequestRejection, PullRequestEvent]] {
          command match {
            case Create(id, project) => IO.pure(PullRequestCreated(id, project, Instant.EPOCH, Anonymous))
            case _                   => IO.raiseError(NotFound)
          }
        } { s =>
          (s, command) match {
            case (_, Create(id, project))                                 => IO.raiseError(AlreadyExists(id, project))
            case (_: PullRequestActive, Update(id, project, rev))         =>
              IO.pure(PullRequestUpdated(id, project, rev, Instant.EPOCH, Anonymous))
            case (_: PullRequestActive, Tag(id, project, rev, targetRev)) =>
              IO.pure(PullRequestTagged(id, project, rev, targetRev, Instant.EPOCH, Anonymous))
            case (_: PullRequestActive, Merge(id, project, rev))          =>
              IO.pure(PullRequestMerged(id, project, rev, Instant.EPOCH, Anonymous))
            case (_, Boom(_, _, message))                                 => IO.terminate(new RuntimeException(message))
            case (_, _: Never)                                            => IO.never
            case (_: PullRequestClosed, _)                                => IO.raiseError(PullRequestAlreadyClosed(command.id, command.project))
          }
        },
      (state: Option[PullRequestState], event: PullRequestEvent) =>
        state.fold[Option[PullRequestState]] {
          event match {
            case PullRequestCreated(id, project, instant, subject) =>
              Some(PullRequestActive(id, project, 1, instant, subject, instant, subject))
            case _                                                 => None
          }
        } { s =>
          (s, event) match {
            case (_, _: PullRequestCreated)                                                 => None
            case (po: PullRequestActive, PullRequestUpdated(_, _, rev, instant, subject))   =>
              Some(po.copy(rev = rev, updatedAt = instant, updatedBy = subject))
            case (po: PullRequestActive, PullRequestTagged(_, _, rev, _, instant, subject)) =>
              Some(po.copy(rev = rev, updatedAt = instant, updatedBy = subject))
            case (po: PullRequestActive, PullRequestMerged(_, _, rev, instant, subject))    =>
              Some(PullRequestClosed(po.id, po.project, rev, po.createdAt, po.createdBy, instant, subject))
            case (_: PullRequestClosed, _)                                                  => None
          }
        }
    )

  sealed trait PullRequestCommand extends Product with Serializable {
    def id: Label
    def project: ProjectRef
  }

  object PullRequestCommand {
    final case class Create(id: Label, project: ProjectRef)                        extends PullRequestCommand
    final case class Update(id: Label, project: ProjectRef, rev: Int)              extends PullRequestCommand
    final case class Tag(id: Label, project: ProjectRef, rev: Int, targetRev: Int) extends PullRequestCommand
    final case class Merge(id: Label, project: ProjectRef, rev: Int)               extends PullRequestCommand

    final case class Boom(id: Label, project: ProjectRef, message: String) extends PullRequestCommand
    final case class Never(id: Label, project: ProjectRef)                 extends PullRequestCommand
  }

  sealed trait PullRequestEvent extends ScopedEvent {
    def id: Label
  }

  object PullRequestEvent {
    final case class PullRequestCreated(id: Label, project: ProjectRef, instant: Instant, subject: Subject)
        extends PullRequestEvent {
      override val rev: Int = 1
    }

    final case class PullRequestUpdated(id: Label, project: ProjectRef, rev: Int, instant: Instant, subject: Subject)
        extends PullRequestEvent

    final case class PullRequestTagged(
        id: Label,
        project: ProjectRef,
        rev: Int,
        targetRev: Int,
        instant: Instant,
        subject: Subject
    ) extends PullRequestEvent

    final case class PullRequestMerged(id: Label, project: ProjectRef, rev: Int, instant: Instant, subject: Subject)
        extends PullRequestEvent

    @nowarn("cat=unused")
    val serializer: Serializer[Label, PullRequestEvent] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration            = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[PullRequestEvent] = deriveConfiguredCodec[PullRequestEvent]
      Serializer(_.id)
    }
  }

  sealed trait PullRequestRejection extends Product with Serializable

  object PullRequestRejection {
    final case object NotFound                 extends PullRequestRejection
    final case class RevisionNotFound(provided: Int, current: Int)            extends PullRequestRejection
    final case class AlreadyExists(id: Label, project: ProjectRef)            extends PullRequestRejection
    final case class PullRequestAlreadyClosed(id: Label, project: ProjectRef) extends PullRequestRejection
  }

  sealed trait PullRequestState extends ScopedState {
    def id: Label

    override def deprecated: Boolean = false

    override def schema: ResourceRef = Latest(schemas + "pull-request.json")

    override def types: Set[IriOrBNode.Iri] = Set(nxv + "PullRequest")
  }

  object PullRequestState {

    final case class PullRequestActive(
        id: Label,
        project: ProjectRef,
        rev: Int,
        createdAt: Instant,
        createdBy: Subject,
        updatedAt: Instant,
        updatedBy: Subject
    ) extends PullRequestState

    final case class PullRequestClosed(
        id: Label,
        project: ProjectRef,
        rev: Int,
        createdAt: Instant,
        createdBy: Subject,
        updatedAt: Instant,
        updatedBy: Subject
    ) extends PullRequestState

    @nowarn("cat=unused")
    val serializer: Serializer[Label, PullRequestState] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration            = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[PullRequestState] = deriveConfiguredCodec[PullRequestState]
      Serializer(_.id)
    }

  }

}
