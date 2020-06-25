package ch.epfl.bluebrain.nexus.iam.types

import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectLabel
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Contexts._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import io.circe.{Encoder, Json}

/**
  * The client caller. It contains the subject and the list of identities (which contains the subject again)
  *
  * @param subject    the identity that performed the call
  * @param identities the set of other identities associated to the ''subject''. E.g.: groups, anonymous, authenticated
  */
final case class Caller(subject: Subject, identities: Set[Identity]) {

  /**
    * Evaluates if the provided ''project'' has the passed ''permission'' on the ''acls''.
    *
   * @param acls         the full list of ACLs
    * @param projectLabel the project to check for permissions validity
    * @param permission   the permission to filter
    */
  def hasPermission(acls: AccessControlLists, projectLabel: ProjectLabel, permission: Permission): Boolean =
    acls.exists(identities, projectLabel, permission)

  /**
    * Filters from the provided ''projects'' the ones where the caller has the passed ''permission'' on the ''acls''.
    *
   * @param acls       the full list of ACLs
    * @param projects   the list of projects to check for permissions validity
    * @param permission the permission to filter
    * @return a set of [[ProjectLabel]]
    */
  def hasPermission(
      acls: AccessControlLists,
      projects: Set[ProjectLabel],
      permission: Permission
  ): Set[ProjectLabel] =
    projects.filter(hasPermission(acls, _, permission))
}

object Caller {

  /**
    * An anonymous caller
    */
  val anonymous: Caller = Caller(Anonymous: Subject, Set[Identity](Anonymous))

  object JsonLd {
    implicit final def callerEncoder(implicit
        I: Encoder[Identity],
        http: HttpConfig
    ): Encoder[Caller] =
      Encoder.instance[Caller] { caller =>
        Json
          .obj(
            "identities" -> Encoder.encodeList(I)(caller.identities.toList.sortBy(_.id.asUri))
          )
          .addContext(iamCtxUri)
          .addContext(resourceCtxUri)
      }
  }
}
