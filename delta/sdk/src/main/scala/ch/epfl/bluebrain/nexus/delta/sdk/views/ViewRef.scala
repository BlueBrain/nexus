package ch.epfl.bluebrain.nexus.delta.sdk.views

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
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

  implicit final val viewRefOrder: Order[ViewRef] = Order.by { viewRef =>
    (viewRef.viewId, viewRef.project)
  }

}
