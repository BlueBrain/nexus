package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

object PullRequest {

  val entityType: EntityType = EntityType("merge-request")

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

    final case class PullRequestMerged(id: Label, project: ProjectRef, rev: Int, instant: Instant, subject: Subject)
        extends PullRequestEvent

    final case class PullRequestClosed(id: Label, project: ProjectRef, rev: Int, instant: Instant, subject: Subject)
        extends PullRequestEvent

    @nowarn("cat=unused")
    val serializer: Serializer[Label, PullRequestEvent] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration            = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[PullRequestEvent] = deriveConfiguredCodec[PullRequestEvent]
      Serializer(_.id)
    }
  }

  sealed trait PullRequestState extends ScopedState {
    def id: Label

    override def deprecated: Boolean = false

    override def schema: ResourceRef = Latest(schemas + "pull-request.json")

    override def types: Set[IriOrBNode.Iri] = Set(nxv + "PullRequest")
  }

  object PullRequestState {

    final case class PullRequestOpen(
        id: Label,
        project: ProjectRef,
        rev: Int,
        createdAt: Instant,
        createdBy: Subject,
        updatedAt: Instant,
        updatedBy: Subject
    ) extends PullRequestState

    final case class PullRequestClose(
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
