package ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchRequest.Input
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.Decoder

import scala.annotation.nowarn

/**
  * Request to get multiple resources
  * @param format
  *   the output format for these resources
  * @param resources
  *   the list of resources
  */
final case class MultiFetchRequest(format: ResourceRepresentation, resources: NonEmptyList[Input]) {}

object MultiFetchRequest {

  def apply(representation: ResourceRepresentation, first: Input, others: Input*) =
    new MultiFetchRequest(representation, NonEmptyList.of(first, others: _*))

  final case class Input(id: ResourceRef, project: ProjectRef)

  @nowarn("cat=unused")
  implicit val multiFetchRequestDecoder: Decoder[MultiFetchRequest] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val cfg: Configuration = Configuration.default
    implicit val inputDecoder       = deriveConfiguredDecoder[Input]
    deriveConfiguredDecoder[MultiFetchRequest]
  }

}
