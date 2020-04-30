package ch.epfl.bluebrain.nexus.cli.postgres.config

import cats.implicits._
import com.github.ghik.silencer.silent
import org.http4s.Uri
import pureconfig.ConfigConvert
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * SQL Projection project configuration.
  * @param sparqlView an optional view reference to query
  * @param types      the collection of type specific configuration
  */
final case class ProjectConfig(
    sparqlView: Option[Uri],
    types: List[TypeConfig]
)

object ProjectConfig {

  @silent
  implicit final val projectConfigConvert: ConfigConvert[ProjectConfig] = {
    implicit val uriConfigConvert: ConfigConvert[Uri] =
      ConfigConvert
        .viaNonEmptyString[Uri](s => Uri.fromString(s).leftMap(err => CannotConvert(s, "Uri", err.details)), _.toString)
    deriveConvert[ProjectConfig]
  }
}
