package ch.epfl.bluebrain.nexus.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import ch.epfl.bluebrain.nexus.acls.AclTarget

trait AclDirectives {

  /**
    * Extracts the [[AclTarget]] from the unmatched path segments.
    */
  @SuppressWarnings(Array("PartialFunctionInsteadOfMatch"))
  def extractAclTarget: Directive1[AclTarget] = extractUnmatchedPath.flatMap { path =>
    AclTarget(path.toString) match {
      case Some(p) => provide(p)
      case None    => reject(validationRejection(s"Invalid Acl target '$path'."))
    }
  }
}

object AclDirectives extends AclDirectives
