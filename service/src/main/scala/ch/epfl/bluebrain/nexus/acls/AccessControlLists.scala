package ch.epfl.bluebrain.nexus.acls

import ch.epfl.bluebrain.nexus.auth.Identity
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.syntax.all._
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.collection.immutable.ListMap

/**
  * Type definition representing a mapping of ACL target to AccessControlList.
  *
  * @param value a map of path and AccessControlList
  */
final case class AccessControlLists(value: Map[AclTarget, AccessControlList]) {

  /**
    * Adds the provided ''acls'' to the current ''value'' and returns a new [[AccessControlLists]] with the added ACLs.
    *
    * @param acls the acls to be added
    */
  def ++(acls: AccessControlLists): AccessControlLists = {
    val toAddKeys   = acls.value.keySet -- value.keySet
    val toMergeKeys = acls.value.keySet -- toAddKeys
    val added       = value ++ acls.value.view.filterKeys(toAddKeys.contains)
    val merged = value.view.filterKeys(toMergeKeys.contains).map {
      case (p, currAcls) => p -> currAcls.++(acls.value(p))
    }
    AccessControlLists(added ++ merged)
  }

  /**
    * Adds a key pair of Path and [[Resource]] to the current ''value'' and returns a new [[AccessControlLists]] with the added acl.
    *
    * @param entry the key pair of Path and ACL to be added
    */
  def +(entry: (AclTarget, AccessControlList)): AccessControlLists = {
    val (target, acl) = entry
    val toAdd         = value.get(target).fold(acl)(_ ++ acl)
    AccessControlLists(value + (target -> toAdd))
  }

  /**
    * @return new [[AccessControlLists]] with the same elements as the current one but sorted by [[AclTarget]] (alphabetically)
    */
  def sorted: AccessControlLists =
    AccessControlLists(ListMap(value.toSeq.sortBy { case (target, _) => target.toString }: _*))

  /**
    * Generates a new [[AccessControlLists]] only containing the provided ''identities''.
    *
    * @param identities the identities to be filtered
    */
  def filter(identities: Set[Identity]): AccessControlLists =
    value.foldLeft(AccessControlLists.empty) {
      case (acc, (p, acl)) => acc + (p -> acl.filter(identities))
    }

  /**
    * @return a new [[AccessControlLists]] containing the ACLs with non empty [[AccessControlList]]
    */
  def removeEmpty: AccessControlLists =
    AccessControlLists(value.foldLeft(Map.empty[AclTarget, AccessControlList]) {
      case (acc, (_, acl)) if acl.value.isEmpty => acc
      case (acc, (p, acl)) =>
        val filteredAcl = acl.value.filterNot { case (_, v) => v.isEmpty }
        if (filteredAcl.isEmpty) acc
        else acc + (p -> AccessControlList(filteredAcl))

    })
}

object AccessControlLists {

  /**
    * An empty [[AccessControlLists]].
    */
  val empty: AccessControlLists = AccessControlLists(Map.empty[AclTarget, AccessControlList])

  /**
    * Convenience factory method to build an ACLs from var args of ''AclTarget'' to ''AccessControlList'' tuples.
    */
  final def apply(tuple: (AclTarget, AccessControlList)*): AccessControlLists = AccessControlLists(tuple.toMap)

  implicit def aclsEncoder(implicit http: HttpConfig): Encoder[AccessControlLists] = Encoder.encodeJson.contramap {
    case AccessControlLists(value) =>
      val arr = value.map {
        case (path, acl) =>
          Json.obj("_path" -> Json.fromString(path.toString)) deepMerge acl.asJson.removeKeys("@context")
      }
      Json
        .obj(nxv.total.prefix -> Json.fromInt(arr.size), nxv.results.prefix -> Json.arr(arr.toSeq: _*))
//        .addContext(resourceCtxUri)
//        .addContext(iamCtxUri)
//        .addContext(searchCtxUri)
  }
}
