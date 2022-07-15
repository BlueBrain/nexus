package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.Fixtures.rcr
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, ElasticSearchViewRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues}
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import scala.concurrent.duration._

trait ElasticSearchViewsSetup extends IOValues with EitherValuable with ConfigFixtures with IOFixedClock {

  private val config = ElasticSearchViewsConfig(
    "http://localhost",
    None,
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, compression = true),
    aggregate,
    keyValueStore,
    PaginationConfig(
      defaultSize = 30,
      sizeLimit = 100,
      fromLimit = 10000
    ),
    cacheIndexing,
    externalIndexing,
    10,
    1.minute,
    Refresh.False,
    2000
  )

  def init(
      fetchContext: FetchContext[ElasticSearchViewRejection],
      perms: Permission*
  )(implicit api: JsonLdApi, as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): ElasticSearchViews =
    init(fetchContext, perms.toSet)

  def init(
      fetchContext: FetchContext[ElasticSearchViewRejection],
      perms: Set[Permission]
  )(implicit
      api: JsonLdApi,
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      sc: Scheduler
  ): ElasticSearchViews = {
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors
      deferred   <- Deferred[Task, ElasticSearchViews]
      cache      <- ElasticSearchViews.cache(config)
      agg        <- ElasticSearchViews.aggregate(
                      PipeConfig.coreConfig.rightValue,
                      config,
                      IO.pure(perms),
                      (_, _) => IO.unit,
                      deferred,
                      ResourceIdCheck.alwaysAvailable
                    )
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      views      <- ElasticSearchViews(deferred, config, eventLog, resolverCtx, cache, agg, fetchContext)
    } yield views
  }.accepted
}
object ElasticSearchViewsSetup extends ElasticSearchViewsSetup
