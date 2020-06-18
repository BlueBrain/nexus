package ch.epfl.bluebrain.nexus.kg.indexing

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, ViewCache}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections._
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

// $COVERAGE-OFF$
object ViewIndexer {

  implicit private val log = Logger[ViewIndexer.type]

  def start[F[_]: Timer](views: Views[F], viewCache: ViewCache[F])(implicit
      projectCache: ProjectCache[F],
      F: Effect[F],
      as: ActorSystem,
      projectInitializer: ProjectInitializer[F],
      adminClient: AdminClient[F],
      config: AppConfig
  ): StreamSupervisor[F, Unit] = {

    implicit val authToken                = config.iam.serviceAccountToken
    implicit val indexing: IndexingConfig = config.keyValueStore.indexing
    implicit val ec: ExecutionContext     = as.dispatcher
    implicit val tm: Timeout              = Timeout(config.keyValueStore.askTimeout)
    val name                              = "view-indexer"

    def toView(event: Event): F[Option[View]] =
      fetchProject(event.organization, event.id.parent, event.subject).flatMap { implicit project =>
        views.fetchView(event.id).value.map {
          case Left(err)   =>
            log.error(s"Error on event '${event.id.show} (rev = ${event.rev})', cause: '${err.msg}'")
            None
          case Right(view) => Some(view)
        }
      }

    val source: Source[PairMsg[Any], _]   = cassandraSource(s"type=${nxv.View.value.show}", name)
    val flow: Flow[PairMsg[Any], Unit, _] = ProgressFlowElem[F, Any]
      .collectCast[Event]
      .groupedWithin(indexing.batch, indexing.batchTimeout)
      .distinct()
      .mergeEmit()
      .mapAsync(toView)
      .collectSome[View]
      .runAsync(viewCache.put)()
      .flow
      .map(_ => ())

    StreamSupervisor.startSingleton(F.delay(source.via(flow)), name)
  }
}
// $COVERAGE-ON$
