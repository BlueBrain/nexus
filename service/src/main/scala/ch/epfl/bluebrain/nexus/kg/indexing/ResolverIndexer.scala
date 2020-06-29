package ch.epfl.bluebrain.nexus.kg.indexing

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, ResolverCache}
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections._
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

// $COVERAGE-OFF$
object ResolverIndexer {

  implicit private val log = Logger[ResolverIndexer.type]

  /**
    * Starts the index process for resolvers across all projects in the system.
    *
    * @param resolvers     the resolvers operations
    * @param resolverCache the distributed cache
    */
  final def start[F[_]: Timer](resolvers: Resolvers[F], resolverCache: ResolverCache[F])(implicit
      projectCache: ProjectCache[F],
      as: ActorSystem,
      F: Effect[F],
      projectInitializer: ProjectInitializer[F],
      adminClient: AdminClient[F],
      config: ServiceConfig
  ): StreamSupervisor[F, Unit] = {
    implicit val authToken: Option[AccessToken] = config.serviceAccount.credentials
    implicit val indexing: IndexingConfig       = config.kg.keyValueStore.indexing
    implicit val ec: ExecutionContext           = as.dispatcher
    implicit val tm: Timeout                    = Timeout(config.kg.keyValueStore.askTimeout)

    val name = "resolver-indexer"

    def toResolver(event: Event): F[Option[Resolver]] =
      fetchProject(event.organization, event.id.parent, event.subject).flatMap { implicit project =>
        resolvers.fetchResolver(event.id).value.map {
          case Left(err)       =>
            log.error(s"Error on event '${event.id.show} (rev = ${event.rev})', cause: '${err.msg}'")
            None
          case Right(resolver) => Some(resolver)
        }
      }

    val source: Source[PairMsg[Any], _]   = cassandraSource(s"type=${nxv.Resolver.value.show}", name)
    val flow: Flow[PairMsg[Any], Unit, _] = ProgressFlowElem[F, Any]
      .collectCast[Event]
      .groupedWithin(indexing.batch, indexing.batchTimeout)
      .distinct()
      .mergeEmit()
      .mapAsync(toResolver)
      .collectSome[Resolver]
      .runAsync(resolverCache.put)()
      .flow
      .map(_ => ())

    StreamSupervisor.startSingleton(F.delay(source.via(flow)), name)
  }
}
// $COVERAGE-ON$
