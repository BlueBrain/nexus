package ch.epfl.bluebrain.nexus

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.ResourceF.ResourceMetadata
import ch.epfl.bluebrain.nexus.acls.AclTarget.RootAcl
import ch.epfl.bluebrain.nexus.auth.Identity.Anonymous
import ch.epfl.bluebrain.nexus.config.AppConfig.{HttpConfig, PermissionsConfig}
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.Aggregate

package object acls {

  /* Type annotations specific to acls */
  type Rejection = AclRejection
  type Event     = AclEvent
  type Command   = AclCommand
  type State     = AclState
  type Agg[F[_]] = Aggregate[F, String, Event, State, Command, Rejection]

  type EventOrRejection = Either[Rejection, Event]
  type MetaOrRejection  = Either[Rejection, ResourceMetadata]

  type Resource    = ResourceF[AccessControlList]
  type ResourceOpt = Option[Resource]

  /**
    * The constant collection of acl types.
    */
  val types: Set[Uri] = Set(nxv.AccessControlList)

  /* Constant permissions */
  val read: Permission  = Permission.unsafe("acls/read")
  val write: Permission = Permission.unsafe("acls/write")

  /**
    * The default [[ResourceF]] of [[AccessControlList]] instantiated on the / location
    * whenever there is nothing already existing on that location
    */
  def defaultResourceOnRoot(implicit http: HttpConfig, pc: PermissionsConfig): Resource =
    ResourceF(
      http.aclsUri.copy(path = http.aclsUri.path ++ RootAcl.toPath),
      0L,
      types,
      Instant.EPOCH,
      Anonymous,
      Instant.EPOCH,
      Anonymous,
      AccessControlList(Anonymous -> pc.minimum)
    )
}
