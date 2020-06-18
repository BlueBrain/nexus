package ch.epfl.bluebrain.nexus.kg.resources

import akka.actor.ActorSystem
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types._
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlLists, Caller}
import ch.epfl.bluebrain.nexus.kg.KgError.InternalError
import ch.epfl.bluebrain.nexus.kg.async.{ProjectAttributesCoordinator, ProjectViewCoordinator}
import ch.epfl.bluebrain.nexus.kg.cache.Caches
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.indexing.View
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.indexing.ViewEncoder._
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.kg.resolve.ResolverEncoder._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.kg.storage.StorageEncoder._
import ch.epfl.bluebrain.nexus.kg.storage.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json
import com.typesafe.scalalogging.Logger
import retry.CatsEffect._
import retry._
import retry.syntax.all._

class ProjectInitializer[F[_]: Timer](
    storages: Storages[F],
    views: Views[F],
    resolvers: Resolvers[F],
    viewCoordinator: ProjectViewCoordinator[F],
    fileAttributesCoordinator: ProjectAttributesCoordinator[F]
)(implicit F: Effect[F], cache: Caches[F], config: AppConfig, as: ActorSystem) {

  private val log         = Logger[this.type]
  private val revK        = nxv.rev.prefix
  private val deprecatedK = nxv.deprecated.prefix
  private val algorithmK  = nxv.algorithm.prefix

  implicit private val policy: RetryPolicy[F]                                            = config.keyValueStore.indexing.retry.retryPolicy[F]
  implicit private val logErrors: (Either[Rejection, Resource], RetryDetails) => F[Unit] =
    (err, details) => F.pure(log.warn(s"Retrying on resource creation with retry details '$details' and error: '$err'"))

  private val wasSuccessful: Either[Rejection, Resource] => Boolean = {
    case Right(_)                       => true
    case Left(_: ResourceAlreadyExists) => true
    case Left(_)                        => false
  }

  /**
    * Set-up the necessary elements in order for a project to be fully usable:
    * 1. Adds the project to the cache
    * 2. Starts the asynchronous process to compute the digest of files with empty Digest
    * 3. Starts the project view coordinator, that will trigger indexing for all the views in that project
    * 4. Creates the default resources: ElasticSearchView, SparqView, InProjectResolver and DiskStorage
    *
    * @param project the targeted project
    * @param subject the caller who created the project
    */
  def apply(project: Project, subject: Subject): F[Unit] = {
    implicit val caller: Caller = Caller(subject, Set(subject))
    implicit val p: Project     = project
    for {
      _ <- cache.project.replace(project)
      _ <- viewCoordinator.start(project)
      _ <- fileAttributesCoordinator.start(project)
      _ <- List(createResolver, createDiskStorage, createElasticSearchView, createSparqlView).sequence
    } yield ()
  }

  private def asJson(view: View): F[Json] =
    view.asGraph.toJson(viewCtx.appendContextOf(resourceCtx)) match {
      case Left(err)   =>
        log.error(s"Could not convert view with id '${view.id}' from Graph back to json. Reason: '$err'")
        F.raiseError(InternalError("Could not decode default view from graph to Json"))
      case Right(json) =>
        F.pure(json.removeKeys(revK, deprecatedK).replaceContext(viewCtxUri).addContext(resourceCtxUri))
    }

  private def asJson(storage: Storage): F[Json] =
    storage.asGraph.toJson(storageCtx.appendContextOf(resourceCtx)) match {
      case Left(err)   =>
        log.error(s"Could not convert storage '${storage.id}' from Graph to json. Reason: '$err'")
        F.raiseError(InternalError("Could not decode default storage from graph to Json"))
      case Right(json) =>
        F.pure(json.removeKeys(revK, deprecatedK, algorithmK).replaceContext(storageCtxUri).addContext(resourceCtxUri))
    }

  private def asJson(resolver: Resolver): F[Json] =
    resolver.asGraph.toJson(resolverCtx.appendContextOf(resourceCtx)) match {
      case Left(err)   =>
        log.error(s"Could not convert resolver '${resolver.id}' from Graph to json. Reason: '$err'")
        F.raiseError(InternalError("Could not decode default in project resolver from graph to Json"))
      case Right(json) =>
        F.pure(json.removeKeys(revK, deprecatedK, algorithmK).replaceContext(resolverCtxUri).addContext(resourceCtxUri))
    }

  private def createElasticSearchView(implicit project: Project, c: Caller): F[Either[Rejection, Resource]] = {
    implicit val acls: AccessControlLists = AccessControlLists.empty
    val view: View                        = ElasticSearchView.default(project.ref)
    asJson(view).flatMap { json =>
      views.create(Id(project.ref, view.id), json, extractUuid = true).value.retryingM(wasSuccessful)
    }
  }

  private def createSparqlView(implicit project: Project, c: Caller): F[Either[Rejection, Resource]] = {
    implicit val acls: AccessControlLists = AccessControlLists.empty
    val view: View                        = SparqlView.default(project.ref)
    asJson(view).flatMap { json =>
      views.create(Id(project.ref, view.id), json, extractUuid = true).value.retryingM(wasSuccessful)
    }
  }

  private def createResolver(implicit project: Project, c: Caller): F[Either[Rejection, Resource]] = {
    val resolver: Resolver = InProjectResolver.default(project.ref)
    asJson(resolver).flatMap { json =>
      resolvers.create(Id(project.ref, resolver.id), json).value.retryingM(wasSuccessful)
    }
  }

  private def createDiskStorage(implicit project: Project, s: Subject): F[Either[Rejection, Resource]] = {
    val storage: Storage = DiskStorage.default(project.ref)
    asJson(DiskStorage.default(project.ref)).flatMap { json =>
      storages.create(Id(project.ref, storage.id), json).value.retryingM(wasSuccessful)
    }
  }
}
