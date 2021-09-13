package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/**
  * View reference.
  *
  * @param project
  *   the project to which the view belongs
  * @param viewId
  *   the view id
  */
final case class ViewRef(project: ProjectRef, viewId: Iri)

object ViewRef {

  implicit final val viewRefEncoder: Codec.AsObject[ViewRef] = deriveCodec[ViewRef]

  implicit final val viewRefJsonLdDecoder: JsonLdDecoder[ViewRef] = deriveJsonLdDecoder[ViewRef]

}
