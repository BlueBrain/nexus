package ch.epfl.bluebrain.nexus.iam

import java.time.Instant

import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, PermissionsConfig}
import ch.epfl.bluebrain.nexus.iam.config.Vocabulary._
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.{Permission, ResourceF, ResourceMetadata}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
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
  val types: Set[AbsoluteIri] = Set(nxv.AccessControlList)

  /* Constant permissions */
  val read: Permission  = Permission.unsafe("acls/read")
  val write: Permission = Permission.unsafe("acls/write")

  /**
    * The default [[ResourceF]] of [[AccessControlList]] instantiated on the / path
    * whenever there is nothing already existing on that path
    */
  def defaultResourceOnSlash(implicit http: HttpConfig, pc: PermissionsConfig): Resource =
    ResourceF(
      http.aclsIri + "/",
      0L,
      types,
      Instant.EPOCH,
      Anonymous,
      Instant.EPOCH,
      Anonymous,
      AccessControlList(Anonymous -> pc.minimum)
    )
}
