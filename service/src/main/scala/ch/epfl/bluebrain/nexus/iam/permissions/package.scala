package ch.epfl.bluebrain.nexus.iam
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.iam.types.{Permission, ResourceF, ResourceMetadata}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.sourcing.Aggregate

package object permissions {

  /* Type annotations specific to permissions */
  type Rejection = PermissionsRejection
  type Event     = PermissionsEvent
  type Command   = PermissionsCommand
  type State     = PermissionsState
  type Agg[F[_]] = Aggregate[F, String, Event, State, Command, Rejection]

  type EventOrRejection = Either[Rejection, Event]
  type MetaOrRejection  = Either[Rejection, ResourceMetadata]

  type Resource    = ResourceF[Set[Permission]]
  type OptResource = Option[Resource]

  /**
    * @return the constant resource id of the permissions set
    */
  final def id(implicit http: HttpConfig): AbsoluteIri = http.permissionsIri

  /**
    * The constant collection of permissions types.
    */
  final val types: Set[AbsoluteIri] = Set(nxv.Permissions)

  /* Constant permissions */
  final val read  = Permission.unsafe("permissions/read")
  final val write = Permission.unsafe("permissions/write")
}
