package ch.epfl.bluebrain.nexus.rdf.jsonld.instances

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.{GraphDecoder, GraphEncoder}
import io.circe.Json
import io.circe.parser._

trait RdfCirceInstances {

  implicit final val jsonGraphEncoder: GraphEncoder[Json] = GraphEncoder.graphEncodeString.contramap(_.noSpaces)
  implicit final val jsonGraphDecoder: GraphDecoder[Json] =
    GraphDecoder.graphDecodeString.emap(str => parse(str).leftMap(_.message))
}

object RdfCirceInstances extends RdfCirceInstances
