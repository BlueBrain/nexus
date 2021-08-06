package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture.config
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Organizations, Permissions, Projects, QuotasDummy}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

trait CompositeViewsSetup extends RemoteContextResolutionFixture with IOFixedClock {

  def initViews(orgs: Organizations, projects: Projects)(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[CompositeViews] =
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[CompositeViewEvent]](EventLogUtils.toEnvelope).hideErrors
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      views      <- CompositeViews(
                      config,
                      eventLog,
                      orgs,
                      projects,
                      _ => UIO.unit,
                      (_, _, _) => UIO.unit,
                      (_, _) => IO.unit,
                      QuotasDummy.neverReached,
                      resolverCtx
                    )
    } yield views

  def initViews(
      orgs: Organizations,
      projects: Projects,
      permissions: Permissions,
      acls: Acls,
      client: ElasticSearchClient,
      crypto: Crypto,
      config: CompositeViewsConfig = CompositeViewsFixture.config
  )(implicit as: ActorSystem[Nothing], baseUri: BaseUri, uuidF: UUIDF, sc: Scheduler): Task[CompositeViews] =
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[CompositeViewEvent]](EventLogUtils.toEnvelope).hideErrors
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      views      <-
        CompositeViews(
          config,
          eventLog,
          permissions,
          orgs,
          projects,
          acls,
          client,
          _ => IO.unit,
          resolverCtx,
          (_, _) => IO.unit,
          QuotasDummy.neverReached,
          crypto
        )
    } yield views
}

object CompositeViewsSetup extends CompositeViewsSetup
