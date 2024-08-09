package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Codec
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

final case class FileDelegationRequest(
    storageId: Iri,
    bucket: String,
    project: ProjectRef,
    id: Iri,
    path: Uri,
    description: FileDescription,
    tag: Option[UserTag]
)

object FileDelegationRequest {
  implicit private val config: Configuration       = Configuration.default
  implicit val codec: Codec[FileDelegationRequest] = deriveConfiguredCodec
}
