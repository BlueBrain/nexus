package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sourcing.Message.MessageRejection.MessageTooLong
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.EphemeralState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import monix.bio.IO

import java.time.Instant
import scala.annotation.nowarn

object Message {
  val entityType: EntityType = EntityType("message")

  def evaluate(c: CreateMessage): IO[MessageRejection, MessageState] =
    IO.raiseWhen(c.text.length > 10)(MessageTooLong(c.id, c.project))
      .as(MessageState(c.id, c.project, c.text, c.from, Instant.EPOCH, Anonymous))

  final case class CreateMessage(id: String, project: ProjectRef, text: String, from: Subject)

  final case class MessageState(
      id: String,
      project: ProjectRef,
      text: String,
      from: Subject,
      createdAt: Instant,
      createdBy: Subject
  ) extends EphemeralState {
    override def schema: ResourceRef = Latest(schemas + "message.json")

    override def types: Set[Iri] = Set(nxv + "Message")
  }

  sealed trait MessageRejection extends Product with Serializable

  object MessageRejection {
    final case object NotFound                                       extends MessageRejection
    final case class AlreadyExists(id: String, project: ProjectRef)  extends MessageRejection
    final case class MessageTooLong(id: String, project: ProjectRef) extends MessageRejection
  }

  object MessageState {
    @nowarn("cat=unused")
    val serializer: Serializer[String, MessageState] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration        = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[MessageState] = deriveConfiguredCodec[MessageState]
      Serializer(_.id)
    }
  }

}
