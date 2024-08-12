package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Codec
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

sealed trait FileDelegationRequest extends Product with Serializable

object FileDelegationRequest {

  final case class TargetLocation(storageId: Iri, bucket: String, path: Uri)

  final case class FileDelegationCreationRequest(
      project: ProjectRef,
      id: Iri,
      targetLocation: TargetLocation,
      description: FileDescription,
      tag: Option[UserTag]
  ) extends FileDelegationRequest

  final case class FileDelegationUpdateRequest(
      project: ProjectRef,
      id: Iri,
      rev: Int,
      targetLocation: TargetLocation,
      description: FileDescription,
      tag: Option[UserTag]
  ) extends FileDelegationRequest

  implicit private val config: Configuration                           = Configuration.default.withDiscriminator("@type")
  implicit val targetLocationodec: Codec[TargetLocation]               = deriveConfiguredCodec
  implicit val fileDelegationRequestodec: Codec[FileDelegationRequest] = deriveConfiguredCodec
}
