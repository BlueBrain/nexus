package ch.epfl.bluebrain.nexus

import akka.http.scaladsl.model.Uri
import cats.implicits._

import scala.util.Try

object AbsoluteUri {

  /**
    * Creates an Akka Http Uri when it is an absolute Uri
    */
  final def apply(string: String): Either[String, Uri] =
    Try(Uri(string)).filter(_.isAbsolute).toEither.leftMap(_ => s"'$string' is not a Uri")
}
