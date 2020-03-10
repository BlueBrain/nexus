package ch.epfl.bluebrain.nexus.rdf

import io.circe.Json

package object graph extends RdfSpec {
  final def jsonWithContext(resourcePath: String): Json =
    jsonContentOf("/graph/context.json") deepMerge jsonContentOf(resourcePath)
}
