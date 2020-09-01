package ch.epfl.bluebrain.nexus.delta.rdf

import monix.bio.IO

package object jsonld {
  type IOErrorOr[A] = IO[JsonLdError, A]
}
