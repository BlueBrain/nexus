package ch.epfl.bluebrain.nexus.acls

import akka.http.scaladsl.model.StatusCodes.{BadRequest, Conflict, NotFound}
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.ResourceRejection
import ch.epfl.bluebrain.nexus.directives.StatusFrom
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.utils.Codecs
import com.github.ghik.silencer.silent
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

sealed abstract class AclRejection(val msg: String) extends ResourceRejection

object AclRejection extends Codecs {

  /**
    * Signals an attempt to append/subtract ACLs that won't change the current state.
    *
    * @param path the target path for the ACL
    */
  final case class NothingToBeUpdated(path: Path)
      extends AclRejection(s"The ACL on path '$path' will not change after applying the provided update.")

  /**
    * Signals an attempt to modify ACLs that do not exists.
    *
    * @param path the target path for the ACL
    */
  final case class AclNotFound(path: Path) extends AclRejection(s"The ACL on path '$path' does not exists.")

  /**
    * Signals an attempt to delete ACLs that are already empty.
    *
    * @param path the target path for the ACL
    */
  final case class AclIsEmpty(path: Path) extends AclRejection(s"The ACL on path '$path' is empty.")

  /**
    * Signals an attempt to interact with an ACL collection with an incorrect revision.
    *
    * @param path the target path for the ACL
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(path: Path, provided: Long, expected: Long)
      extends AclRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the ACL on path '$path' may have been updated since last seen."
      )

  /**
    * Signals an attempt to create/replace/append/subtract ACL collection which contains void permissions.
    *
    * @param path the target path for the ACL
    */
  final case class AclCannotContainEmptyPermissionCollection(path: Path)
      extends AclRejection(s"The ACL for path '$path' cannot contain an empty permission collection.")

  /**
    * Signals that an acl operation could not be performed because of unknown referenced permissions.
    *
    * @param permissions the unknown permissions
    */
  final case class UnknownPermissions(permissions: Set[Permission])
      extends AclRejection(
        s"Some of the permissions specified are not known: '${permissions.mkString("\"", ", ", "\"")}'"
      )

  @silent // rejectionConfig is not recognized as being used
  implicit val aclRejectionEncoder: Encoder[AclRejection] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
//    val enc                                     = deriveConfiguredEncoder[AclRejection].mapJson(_ addContext errorCtxUri)
    val enc = deriveConfiguredEncoder[AclRejection]
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }

  implicit val aclRejectionStatusFrom: StatusFrom[AclRejection] =
    StatusFrom {
      case _: NothingToBeUpdated                        => BadRequest
      case _: AclIsEmpty                                => BadRequest
      case _: AclCannotContainEmptyPermissionCollection => BadRequest
      case _: AclNotFound                               => NotFound
      case _: IncorrectRev                              => Conflict
      case _: UnknownPermissions                        => BadRequest
    }
}
