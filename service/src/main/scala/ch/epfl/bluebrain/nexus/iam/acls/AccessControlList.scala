package ch.epfl.bluebrain.nexus.iam.acls

import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig
import io.circe._
import io.circe.syntax._

/**
  * Type definition representing a mapping of identities to permissions for a specific resource.
  *
  * @param value a map of identity and Set of Permission
  */
final case class AccessControlList(value: Map[Identity, Set[Permission]]) {

  /**
    * Adds the provided ''acl'' to the current ''value'' and returns a new [[AccessControlList]] with the added ACL.
    *
    * @param acl the acl to be added
    */
  def ++(acl: AccessControlList): AccessControlList = {
    val toAddKeys   = acl.value.keySet -- value.keySet
    val toMergeKeys = acl.value.keySet -- toAddKeys
    val added       = value ++ acl.value.view.filterKeys(toAddKeys.contains)
    val merged      = value.view.filterKeys(toMergeKeys.contains).map {
      case (ident, perms) => ident -> (perms ++ acl.value.getOrElse(ident, Set.empty))
    }
    AccessControlList(added ++ merged)
  }

  /**
    * removes the provided ''acl'' from the current ''value'' and returns a new [[AccessControlList]] with the subtracted ACL.
    *
    * @param acl the acl to be subtracted
    */
  def --(acl: AccessControlList): AccessControlList =
    AccessControlList(acl.value.foldLeft(value) {
      case (acc, (p, aclToSubtract)) =>
        acc.get(p).map(_ -- aclToSubtract) match {
          case Some(remaining) if remaining.isEmpty => acc - p
          case Some(remaining)                      => acc + (p -> remaining)
          case None                                 => acc
        }
    })

  /**
    * @return a collapsed Set of [[Permission]] from all the identities
    */
  def permissions: Set[Permission] = value.foldLeft(Set.empty[Permission]) { case (acc, (_, perms)) => acc ++ perms }

  /**
    * @return ''true'' if the underlying list is empty or if any pair is found with an empty permissions set
    */
  def hasVoidPermissions: Boolean = value.isEmpty || value.exists { case (_, perms) => perms.isEmpty }

  /**
    * Generates a new [[AccessControlList]] only containing the provided ''identities''.
    *
    * @param identities the identities to be filtered
    */
  def filter(identities: Set[Identity]): AccessControlList =
    AccessControlList(value.view.filterKeys(identities.contains).toMap)

  /**
    * Determines if this contains the argument ''permission'' for at least one of the provided ''identities''.
    *
    * @param identities the identities to consider for having the permission
    * @param permission the permission to check
    * @return true if at least one of the provided identities has the provided permission
    */
  def hasPermission(identities: Set[Identity], permission: Permission): Boolean =
    value.exists {
      case (id, perms) => identities.contains(id) && perms.contains(permission)
    }
}

object AccessControlList {

  /**
    * An empty [[AccessControlList]].
    */
  val empty: AccessControlList = AccessControlList(Map.empty[Identity, Set[Permission]])

  /**
    * Convenience factory method to build an ACL from var args of ''Identity'' to ''Permissions'' tuples.
    */
  def apply(acl: (Identity, Set[Permission])*): AccessControlList =
    AccessControlList(acl.toMap)

  def aclArrayEncoder(implicit http: HttpConfig): Encoder[AccessControlList] =
    Encoder.encodeJson.contramap {
      case AccessControlList(value) =>
        val acl = value.map {
          case (identity, perms) => Json.obj("identity" -> identity.asJson, "permissions" -> perms.asJson)
        }
        Json.arr(acl.toSeq: _*)
    }

  implicit def aclEncoder(implicit http: HttpConfig): Encoder[AccessControlList] =
    aclArrayEncoder.mapJson { array => Json.obj("acl" -> array) }

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

  object JsonLd {
    implicit def aclAsArrayEncoder(implicit http: HttpConfig): Encoder[AccessControlList] =
      aclArrayEncoder(http)
  }
}
