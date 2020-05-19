package ch.epfl.bluebrain.nexus.acls

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import akka.http.scaladsl.model.Uri.Path
import cats.Applicative
import ch.epfl.bluebrain.nexus.auth.Identity
import ch.epfl.bluebrain.nexus.config.AppConfig.{HttpConfig, PermissionsConfig}
import ch.epfl.bluebrain.nexus.syntax.all._

import scala.annotation.tailrec

trait AclsIndex[F[_]] {

  /**
    * Replaces the current index entry for id ''path'' with the value ''acl''.
    *
    * @param path        the id of the index entry to be replaced
    * @param aclResource the value of the index entry to be replaced
    * @return F(true) if the update was successfully performed, F(false) otherwise
    */
  def replace(path: Path, aclResource: Resource): F[Boolean]

  /**
    * Fetches the [[AccessControlLists]] of the provided ''path'' with some filtering options.
    * The ''path'' can contain the '*' character. This is used to do listings. E.g.: If we have ACLs on path ''/a/b'' and path ''/a/c'',
    * providing a path ''/a/\*'' will target both ''/a/b'' and ''/a/c''.
    *
    * When ''self'' = true, the result only contains ACLs for the provided ''identities''.
    * When ''self'' = false, the result contains ACLs for all the identities that the provided ''identities'' have access to retrieve.
    * The access to a certain identity to retrieve the ACL of another identity depends on the identity having the ''Own'' permission or not.
    *
    * When ''ancestors'' = true, the result contains ACLs for the provided ''path'' and all the parent paths.
    * When ''ancestors'' = false, the result only contains ACLs for the provided ''path''.
    *
    * @param path       the path where the ACLs are going to be looked up
    * @param ancestors  flag to decide whether or not ancestor paths should be included in the response
    * @param self       flag to decide whether or not ancestor other identities than the provided ones should be included in the response
    * @param identities the provided identities
    */
  def get(path: Path, ancestors: Boolean, self: Boolean)(implicit identities: Set[Identity]): F[AccessControlLists]

}

object AclsIndex {

  implicit private[AclsIndex] class ConcurrentHashMapSyntax[K, V](private val map: ConcurrentHashMap[K, V])
      extends AnyVal {
    def getSafe(key: K): Option[V] = Option(map.get(key))
  }

  /**
    * An in memory implementation of [[AclsIndex]]. It uses a tree structure, stored in the ''tree'' map.
    * Every key on the map is a [[Path]] and its values are a set of children [[Path]]s. In this way one can
    * navigate down the tree.
    *
    * @param tree the data structure used to build the tree with the parent paths and the children paths
    * @param acls a data structure used to store the ACLs for a path
    */
  private class InMemoryAclsTree[F[_]](
      tree: ConcurrentHashMap[Path, Set[Path]],
      acls: ConcurrentHashMap[Path, Resource]
  )(
      implicit F: Applicative[F],
      pc: PermissionsConfig,
      http: HttpConfig
  ) extends AclsIndex[F] {

    private val any = "*"

    override def replace(path: Path, aclResource: Resource): F[Boolean] = {
      @tailrec def inner(p: Path, children: Set[Path]): Unit = {
        tree.merge(p, children, (current, _) => current ++ children)
        if (!(p.isEmpty || p == Path./)) inner(p.parent, Set(p))
      }

      val rev = aclResource.rev

      val f: BiFunction[Resource, Resource, Resource] = (curr, _) =>
        curr match {
          case c if rev > c.rev => aclResource
          case other            => other
        }
      val updated = acls.merge(path, aclResource, f)

      val update = updated == aclResource
      if (update) inner(path, Set.empty)
      F.pure(update)
    }

    override def get(path: Path, ancestors: Boolean, self: Boolean)(
        implicit identities: Set[Identity]
    ): F[AccessControlLists] = {

      def removeNotOwn(currentAcls: AccessControlLists): AccessControlLists = {
        def containsAclsRead(acl: AccessControlList): Boolean =
          acl.value.exists { case (ident, perms) => identities.contains(ident) && perms.contains(read) }

        val (_, result) = currentAcls.sorted.value
          .foldLeft(Set.empty[Path] -> AccessControlLists.empty) {
            case ((ownPaths, acc), entry @ (p, _)) if ownPaths.exists(p.startsWith) => ownPaths     -> (acc + entry)
            case ((ownPaths, acc), entry @ (p, acl)) if containsAclsRead(acl.value) => ownPaths + p -> (acc + entry)
            case ((ownPaths, acc), (p, acl))                                        => ownPaths     -> (acc + (p -> acl.map(_.filter(identities))))
          }
        result
      }

      F.pure {
        if (self) {
          val result = if (ancestors) getWithAncestors(path) else get(path)
          result.filter(identities).removeEmpty
        } else {
          val result = removeNotOwn(getWithAncestors(path))
          if (ancestors)
            result.removeEmpty
          else
            AccessControlLists(result.value.view.filterKeys(_.length == path.length).toMap).removeEmpty
        }
      }
    }

    private def getWithAncestors(path: Path): AccessControlLists = {
      val currentAcls = get(path)
      if (path.isEmpty || path == Path./) currentAcls
      else currentAcls ++ getWithAncestors(path.parent)
    }

    private def pathOf(segments: Seq[String]): Path =
      if (segments.isEmpty) Path./ else Path(segments.mkString("/"))

    private def get(path: Path): AccessControlLists = {
      val pathSegments = path.segments

      def inner(toConsume: Seq[String]): AccessControlLists = {
        if (toConsume.contains(any)) {
          val consumed = toConsume.takeWhile(_ != any)
          val path     = pathOf(consumed)
          tree.getSafe(path) match {
            case Some(children) if consumed.size + 1 == pathSegments.size =>
              AccessControlLists(children.foldLeft(Map.empty[Path, Resource]) { (acc, p) =>
                acls.getSafe(p).map(r => acc + (p -> r)).getOrElse(acc)
              })
            case Some(children) =>
              children.foldLeft(AccessControlLists.empty) {
                case (acc, childPath) if !childPath.endsWithSlash =>
                  childPath.lastSegment match {
                    case Some(last) =>
                      val toConsumeNew =
                        (consumed :+ last) ++ pathSegments.takeRight(pathSegments.size - 1 - consumed.size)
                      acc ++ inner(toConsumeNew)
                    case None => acc
                  }
                case (acc, _) => acc
              }
            case None => initialAcls(path)
          }
        } else {
          val path = pathOf(toConsume)
          acls.getSafe(path).map(r => AccessControlLists(path -> r)).getOrElse(initialAcls(path))
        }
      }

      inner(pathSegments)
    }

    private def initialAcls(path: Path): AccessControlLists =
      if (path == Path./) AccessControlLists(Path./ -> defaultResourceOnSlash) else AccessControlLists.empty
  }

  /**
    * Constructs an in memory implementation of [[AclsIndex]]
    *
    */
  final def apply[F[_]: Applicative](implicit pc: PermissionsConfig, http: HttpConfig): AclsIndex[F] =
    new InMemoryAclsTree[F](new ConcurrentHashMap[Path, Set[Path]](), new ConcurrentHashMap[Path, Resource]())
}
