package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{DecodingFailed, InvalidJsonLdFormat, ViewNotFound}

package object routes {

  val decodingFailedOrViewNotFound: PartialFunction[CompositeViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }
}
