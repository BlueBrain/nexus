package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

final case class AclValues(value: Seq[(Identity, Set[Permission])])

object AclValues {

  final private case class IdentityPermissions(identity: Identity, permissions: Set[Permission])

  implicit private val identityPermsDecoder: Decoder[IdentityPermissions] = {
    implicit val config: Configuration = Configuration.default.withStrictDecoding
    deriveConfiguredDecoder[IdentityPermissions]
  }

  implicit val aclValuesDecoder: Decoder[AclValues] =
    Decoder
      .decodeSeq[IdentityPermissions]
      .map(seq => AclValues(seq.map(value => value.identity -> value.permissions)))

}
