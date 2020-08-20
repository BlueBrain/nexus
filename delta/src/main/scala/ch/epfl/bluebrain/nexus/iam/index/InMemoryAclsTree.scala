package ch.epfl.bluebrain.nexus.iam.index

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import cats.Applicative
import ch.epfl.bluebrain.nexus.iam.acls._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.PermissionsConfig
import ch.epfl.bluebrain.nexus.iam.index.InMemoryAclsTree._
import ch.epfl.bluebrain.nexus.iam.syntax._
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path.Segment
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig

import scala.annotation.tailrec

/**
  * An in memory implementation of [[AclsIndex]]. It uses a tree structure, stored in the ''tree'' map.
  * Every key on the map is a [[Path]] and its values are a set of children [[Path]]s. In this way one can
  * navigate down the tree.
  *
  * @param tree the data structure used to build the tree with the parent paths and the children paths
  * @param acls a data structure used to store the ACLs for a path
  */
class InMemoryAclsTree[F[_]] private (
    tree: ConcurrentHashMap[Path, Set[Path]],
    acls: ConcurrentHashMap[Path, Resource]
)(implicit
    F: Applicative[F],
    pc: PermissionsConfig,
    http: HttpConfig
) extends AclsIndex[F] {

  private val any = "*"

  override def replace(path: Path, aclResource: Resource): F[Boolean] = {
    @tailrec
    def inner(p: Path, children: Set[Path]): Unit = {
      tree.merge(p, children, (current, _) => current ++ children)
      if (!(p.isEmpty || p == Path./))
        inner(p.parent, Set(p))
    }

    val rev = aclResource.rev

    val f: BiFunction[Resource, Resource, Resource] = (curr, _) =>
      curr match {
        case c if rev > c.rev => aclResource
        case other            => other
      }
    val updated                                     = acls.merge(path, aclResource, f)

    val update = updated == aclResource
    if (update) inner(path, Set.empty)
    F.pure(update)
  }

  override def get(path: Path, ancestors: Boolean, self: Boolean)(implicit
      identities: Set[Identity]
  ): F[AccessControlLists] = {

    def removeNotOwn(currentAcls: AccessControlLists): AccessControlLists = {
      def containsAclsRead(acl: AccessControlList): Boolean =
        acl.value.exists { case (ident, perms) => identities.contains(ident) && perms.contains(read) }

      val (_, result)                                       = currentAcls.sorted.value
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
          AccessControlLists(result.value.view.filterKeys(_.size == path.size).toMap).removeEmpty
      }
    }
  }

  private def getWithAncestors(path: Path): AccessControlLists = {
    val currentAcls = get(path)
    if (path.isEmpty || path == Path./) currentAcls
    else currentAcls ++ getWithAncestors(path.parent)
  }

  private def pathOf(segments: Vector[String]): Path =
    if (segments.isEmpty) Path./ else segments.foldLeft[Path](Path.Empty)(_ / _)

  private def get(path: Path): AccessControlLists = {
    val segments = path.segments.toVector

    def inner(toConsume: Vector[String]): AccessControlLists = {
      if (toConsume.contains(any)) {
        val consumed = toConsume.takeWhile(_ != any)
        val path     = pathOf(consumed)
        tree.getSafe(path) match {
          case Some(children) if consumed.size + 1 == segments.size =>
            AccessControlLists(children.foldLeft(Map.empty[Path, Resource]) { (acc, p) =>
              acls.getSafe(p).map(r => acc + (p -> r)).getOrElse(acc)
            })
          case Some(children)                                       =>
            children.foldLeft(AccessControlLists.empty) {
              case (acc, Segment(head, _)) =>
                val toConsumeNew = (consumed :+ head) ++ segments.takeRight(segments.size - 1 - consumed.size)
                acc ++ inner(toConsumeNew)
              case (acc, _)                => acc
            }
          case None                                                 => initialAcls(path)
        }
      } else {
        val path = pathOf(toConsume)
        acls.getSafe(path).map(r => AccessControlLists(path -> r)).getOrElse(initialAcls(path))
      }
    }

    inner(segments)
  }

  private def initialAcls(path: Path): AccessControlLists =
    if (path == Path./) AccessControlLists(Path./ -> defaultResourceOnSlash) else AccessControlLists.empty
}

object InMemoryAclsTree {

  implicit private[index] class ConcurrentHashMapSyntax[K, V](private val map: ConcurrentHashMap[K, V]) extends AnyVal {
    def getSafe(key: K): Option[V] = Option(map.get(key))
  }

  /**
    * Constructs an in memory implementation of [[AclsIndex]]
    *
    */
  final def apply[F[_]: Applicative](implicit pc: PermissionsConfig, http: HttpConfig): InMemoryAclsTree[F] =
    new InMemoryAclsTree(new ConcurrentHashMap[Path, Set[Path]](), new ConcurrentHashMap[Path, Resource]())
}
