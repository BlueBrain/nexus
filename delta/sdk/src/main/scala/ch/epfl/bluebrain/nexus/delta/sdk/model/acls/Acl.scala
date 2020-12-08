package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Acl.Metadata
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import scala.annotation.nowarn

/**
  * An Access Control List codified as an address and a map where the keys are [[Identity]] and the values are a set of [[Permission]].
  * It specifies which permissions are applied for which identities in which address.
  */
final case class Acl(address: AclAddress, value: Map[Identity, Set[Permission]]) {

  /**
    * Adds the provided ''acl'' to the current ''value'' and returns a new [[Acl]] with the added ACL.
    *
    * @param acl the acl to be added
    */
  def ++(acl: Acl): Acl =
    if (acl.address == address)
      copy(value = acl.value.foldLeft(value) { case (acc, (id, permsToAdd)) =>
        acc.updatedWith(id)(perms => Some(perms.fold(permsToAdd)(_ ++ permsToAdd)))
      })
    else this

  /**
    * removes the provided ''acl'' from the current ''value'' and returns a new [[Acl]] with the subtracted ACL.
    *
    * @param acl the acl to be subtracted
    */
  def --(acl: Acl): Acl =
    if (acl.address == address)
      copy(value = acl.value.foldLeft(value) { case (acc, (id, permsToDelete)) =>
        acc.updatedWith(id)(_.map(_ -- permsToDelete).filter(_.nonEmpty))
      })
    else this

  /**
    * @return a collapsed Set of [[Permission]] from all the identities
    */
  def permissions: Set[Permission] =
    value.foldLeft(Set.empty[Permission]) { case (acc, (_, perms)) => acc ++ perms }

  /**
    * @return ''true'' if the underlying map is empty or if any permission set is empty
    */
  def hasEmptyPermissions: Boolean =
    value.isEmpty || value.exists { case (_, perms) => perms.isEmpty }

  /**
    * @return ''true'' if the underlying map is empty or if every permission set is empty
    */
  def isEmpty: Boolean             =
    value.isEmpty || value.forall { case (_, perms) => perms.isEmpty }

  /**
    * @return ''true'' if the underlying map is not empty and every permission set is not empty
    */
  def nonEmpty: Boolean            =
    !isEmpty

  /**
    * @return a new [[Acl]] without the identities that have empty permission sets
    */
  def removeEmpty(): Acl                     =
    Acl(address, value.filter { case (_, perms) => perms.nonEmpty })

  /**
    * Filters the passed identities from the current value map.
    */
  def filter(identities: Set[Identity]): Acl =
    Acl(address, value.view.filterKeys(identities.contains).toMap)

  /**
    * Determines if the current ACL contains the argument ''permission'' for at least one of the provided ''identities''.
    *
    * @param identities the identities to consider for having the permission
    * @param permission the permission to check
    * @return true if at least one of the provided identities has the provided permission
    */
  def hasPermission(identities: Set[Identity], permission: Permission): Boolean =
    value.exists { case (id, perms) =>
      identities.contains(id) && perms.contains(permission)
    }

  /**
    * @return [[Acl]] metadata
    */
  def metadata: Metadata = Metadata(address)
}

object Acl {

  /**
    * Convenience factory method to build an ACL from var args of ''Identity'' to ''Permissions'' tuples.
    */
  def apply(address: AclAddress, acl: (Identity, Set[Permission])*): Acl =
    Acl(address, acl.toMap)

  /**
    * Acl metadata.
    *
    * @param path the address of this ACL
    */
  final case class Metadata(path: AclAddress)

  implicit def aclEncoder(implicit base: BaseUri): Encoder.AsObject[Acl] =
    Encoder.AsObject.instance { acl =>
      JsonObject(
        "_path" -> acl.address.asJson,
        "acl"   -> Json.fromValues(
          acl.value.map { case (identity, permissions) =>
            Json.obj("identity" -> identity.asJson, "permissions" -> permissions.asJson)
          }
        )
      )

    }

  val context: ContextValue = ContextValue(contexts.acls)

  implicit def aclJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[Acl] =
    JsonLdEncoder.computeFromCirce(context)

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "path" => nxv.path.prefix
    case other  => other
  })

  implicit private val aclMetadataEncoder: Encoder.AsObject[Metadata] = deriveConfiguredEncoder[Metadata]

  implicit val aclMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.metadata))
}
