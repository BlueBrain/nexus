package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewRejection, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import scala.concurrent.duration._

trait BlazegraphViewsSetup extends IOValues with ConfigFixtures with IOFixedClock with Fixtures {

  val config = BlazegraphViewsConfig(
    "http://localhost",
    None,
    httpClientConfig,
    httpClientConfig,
    1.second,
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    10,
    1.minute
  )

  def init(
      fetchContext: FetchContext[BlazegraphViewRejection],
      perms: Permission*
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): BlazegraphViews =
    init(fetchContext, perms.toSet)

  def init(
      fetchContext: FetchContext[BlazegraphViewRejection],
      perms: Set[Permission]
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): BlazegraphViews = {
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
      deferred   <- Deferred[Task, BlazegraphViews]
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      cache       = BlazegraphViews.cache(config)
      agg        <- BlazegraphViews.aggregate(config, deferred, IO.pure(perms), ResourceIdCheck.alwaysAvailable)
      views      <- BlazegraphViews(config, eventLog, resolverCtx, cache, deferred, agg, fetchContext, _ => IO.unit)
    } yield views
  }.accepted
}

object BlazegraphViewsSetup extends BlazegraphViewsSetup
