package ch.epfl.bluebrain.nexus.iam.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path.{Empty, Slash}

trait AclDirectives {

  /**
    * Extracts the [[Path]] from the unmatched segments.
    * Remove the trailing slash if the path is not empty. E.g.: /my/path/ -> /my/path
    */
  def extractResourcePath: Directive1[Path] = extractUnmatchedPath.flatMap { path =>
    path.asIriPath match {
      case p if p.asString.contains("//") =>
        reject(validationRejection(s"Path '${p.asString}' cannot contain double slash."))
      case p if p.isEmpty         => provide(Path./)
      case Slash(p) if p != Empty => provide(p)
      case p                      => provide(p)
    }
  }
}

object AclDirectives extends AclDirectives
