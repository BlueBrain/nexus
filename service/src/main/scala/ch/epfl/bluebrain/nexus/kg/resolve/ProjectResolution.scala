package ch.epfl.bluebrain.nexus.kg.resolve

import cats.Monad
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.iriResolution
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._
import com.typesafe.scalalogging.Logger
import monix.eval.Task

/**
  * Resolution for a given project
  *
  * @param repo             the resources repository
  * @param resolverCache    the resolver cache
  * @param projectCache     the project cache
  * @param staticResolution the static resolutions
  * @param aclCache         the acl cache
  * @tparam F the monadic effect type
  */
class ProjectResolution[F[_]](
    repo: Repo[F],
    resolverCache: ResolverCache[F],
    projectCache: ProjectCache[F],
    staticResolution: Resolution[F],
    aclCache: AclsCache[F]
)(implicit F: Monad[F]) {

  private val logger = Logger[this.type]

  /**
    * Looks up the collection of defined resolvers for the argument project
    * and generates an aggregated [[Resolution]] out of them.
    *
    * @param ref  the project reference
    * @return a new [[Resolution]] which is composed by all the resolutions generated from
    *         the resolvers found for the given ''projectRef''
    */
  def apply(ref: ProjectRef): Resolution[F] =
    new Resolution[F] {

      private val resolution = resolverCache.get(ref).flatMap(toCompositeResolution)

      def resolve(ref: Ref): F[Option[Resource]] =
        resolution.flatMap(_.resolve(ref))
    }

  private def toCompositeResolution(resolvers: List[Resolver]): F[CompositeResolution[F]] =
    resolvers
      .filterNot(_.deprecated)
      .flatMap(resolverResolution)
      .sequence
      .map(list => CompositeResolution(staticResolution :: list))

  private def resolverResolution(resolver: Resolver): Option[F[Resolution[F]]] =
    resolver match {
      case r: InProjectResolver    => Some(F.pure(InProjectResolution[F](r.ref, repo)))
      case r: CrossProjectResolver =>
        val refs = r.projectsBy[ProjectRef]
        Some(aclCache.list.map(MultiProjectResolution(repo, refs, r.resourceTypes, r.identities, projectCache, _)))
      case other                   =>
        logger.error(s"A corrupted resolver was found in the cache '$other'")
        None

    }

  /**
    * Generates an aggregated [[Resolution]] out of the provided resolver.
    *
    * @param resolver the resolver
    * @return a new [[Resolution]] which is composed by the resolution generated from the provided resolver
    */
  def apply(resolver: Resolver): Resolution[F] =
    new Resolution[F] {

      private val resolution = toCompositeResolution(List(resolver))

      def resolve(ref: Ref): F[Option[Resource]] =
        resolution.flatMap(_.resolve(ref))
    }

}

object ProjectResolution {

  /**
    * @param repo          the resources repository
    * @param resolverCache the resolver cache
    * @param projectCache  the project cache
    * @param aclCache      the acl cache
    * @return a new [[ProjectResolution]] for the effect type [[Task]]
    */
  def task(
      repo: Repo[Task],
      resolverCache: ResolverCache[Task],
      projectCache: ProjectCache[Task],
      aclCache: AclsCache[Task]
  ): ProjectResolution[Task] =
    new ProjectResolution(repo, resolverCache, projectCache, StaticResolution[Task](iriResolution), aclCache)

}
