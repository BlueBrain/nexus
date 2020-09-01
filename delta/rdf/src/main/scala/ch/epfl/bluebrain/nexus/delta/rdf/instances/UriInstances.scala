package ch.epfl.bluebrain.nexus.delta.rdf.instances

import akka.http.scaladsl.model.Uri
import io.circe.{Decoder, Encoder}

import scala.util.Try

trait UriInstances {
  implicit final val uriDecoder: Decoder[Uri] = Decoder.decodeString.emapTry(s => Try(Uri(s)))
  implicit final val uriEncoder: Encoder[Uri] = Encoder.encodeString.contramap(_.toString())
}

object UriInstances extends UriInstances
