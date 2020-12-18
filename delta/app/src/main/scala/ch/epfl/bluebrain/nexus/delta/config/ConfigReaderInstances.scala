package ch.epfl.bluebrain.nexus.delta.config

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import scala.annotation.tailrec
import scala.util.Try

/**
  * Common ConfigReader instances for types that are not defined in the app module.
  */
trait ConfigReaderInstances {

  implicit final val baseUriConfigReader: ConfigReader[BaseUri] = {
    @tailrec
    def rec(uri: Uri, consumed: Path, remaining: Path): Either[CannotConvert, BaseUri] = remaining match {
      case Path.Empty                                              => Right(BaseUri(uri.withPath(consumed)))
      case Path.Slash(tail)                                        => rec(uri, consumed, tail)
      case Path.Segment(head, Path.Slash(Path.Empty) | Path.Empty) =>
        Label(head)
          .leftMap(err => CannotConvert(head, classOf[Label].getSimpleName, err.getMessage))
          .map(label => BaseUri(uri.withPath(consumed).withoutFragment.copy(rawQueryString = None), Some(label)))
      case Path.Segment(head, Path.Slash(Path.Slash(other)))       =>
        rec(uri, consumed, Path.Segment(head, Path.Slash(other)))
      case Path.Segment(head, Path.Slash(other))                   =>
        rec(uri, consumed ?/ head, other)
    }

    ConfigReader.fromString(str =>
      Try(Uri(str)).toEither
        .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
        .flatMap { uri =>
          if (uri.isAbsolute) rec(uri, Path.Empty, uri.path)
          else Left(CannotConvert(str, classOf[Uri].getSimpleName, "The value must be an absolute Uri."))
        }
    )
  }

}

object ConfigReaderInstances extends ConfigReaderInstances
