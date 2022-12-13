package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/**
  * Defines metadata for a projection
  * @param module
  *   the module the projection lives in
  * @param name
  *   the name of the projection
  * @param project
  *   the optional project linked to the projection (ex: the project of a view)
  * @param resourceId
  *   the optional resource identifier linked to the projection (ex: a view)
  */
final case class ProjectionMetadata(module: String, name: String, project: Option[ProjectRef], resourceId: Option[Iri])

object ProjectionMetadata {

  /**
    * Metadata for a projection that is not related to a given project or resource
    * @param module
    *   the module the projection lives in
    * @param name
    *   the name of the projection
    */
  def apply(module: String, name: String): ProjectionMetadata = ProjectionMetadata(module, name, None, None)

  implicit final val projectionMetadataEncoder: Encoder[ProjectionMetadata] = deriveEncoder
}
