package ch.epfl.bluebrain.nexus.migration.v1_4.events.iam

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe._

/**
  * Type definition representing a mapping of identities to permissions for a specific resource.
  *
  * @param value a map of identity and Set of Permission
  */
final case class AccessControlList(value: Map[Identity, Set[Permission]])

object AccessControlList {

  implicit val aclDecoder: Decoder[AccessControlList] = {
    def inner(hcc: HCursor): Decoder.Result[(Identity, Set[Permission])] =
      for {
        identity <- hcc.get[Identity]("identity")
        perms    <- hcc.get[Set[Permission]]("permissions")
      } yield identity -> perms

    Decoder.instance { hc =>
      for {
        arr <- hc.downField("acl").focus.flatMap(_.asArray).toRight(DecodingFailure("acl field not found", hc.history))
        acl <- arr.foldM(Map.empty[Identity, Set[Permission]]) { case (acc, j) => inner(j.hcursor).map(acc + _) }
      } yield AccessControlList(acl)
    }
  }

}
