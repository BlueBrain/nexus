package ch.epfl.bluebrain.nexus.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._

trait AclDirectives {

  /**
    * Extracts the [[Path]] from the unmatched segments.
    * Remove the trailing slash if the path is not empty. E.g.: /my/path/ -> /my/path
    */
  @SuppressWarnings(Array("PartialFunctionInsteadOfMatch"))
  def extractResourcePath: Directive1[Path] = extractUnmatchedPath.flatMap { path =>
    path match {
      case p if p.toString().contains("//") =>
        reject(validationRejection(s"Path '$p' cannot contain double slash."))
      case p if p.isEmpty                     => provide(Path./)
      case p if p.endsWithSlash && !p.isEmpty => provide(Path(p.toString().dropRight(1)))
      case p                                  => provide(p)
    }
  }
}

object AclDirectives extends AclDirectives
