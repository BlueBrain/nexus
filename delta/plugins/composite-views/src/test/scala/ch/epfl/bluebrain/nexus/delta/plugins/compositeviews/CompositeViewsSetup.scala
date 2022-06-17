package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture.config
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewEvent, CompositeViewSource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import fs2.Stream
import io.circe.Decoder
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

trait CompositeViewsSetup extends Fixtures with IOFixedClock {

  private val deltaClient = new DeltaClient {
    override def projectCount(source: CompositeViewSource.RemoteProjectSource): HttpResult[ProjectCount] =
      IO.pure(ProjectCount.emptyEpoch)

    override def checkEvents(source: CompositeViewSource.RemoteProjectSource): HttpResult[Unit] = IO.unit

    override def events[A: Decoder](
        source: CompositeViewSource.RemoteProjectSource,
        offset: Offset
    ): Stream[Task, (Offset, A)] = Stream.empty

    override def resourceAsNQuads(
        source: CompositeViewSource.RemoteProjectSource,
        id: IriOrBNode.Iri,
        tag: Option[UserTag]
    ): HttpResult[Option[NQuads]] = IO.none
  }

  def initViews(orgs: Organizations, projects: Projects)(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[CompositeViews] =
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[CompositeViewEvent]](EventLogUtils.toEnvelope).hideErrors
      agg        <- CompositeViews.aggregate(config, _ => UIO.unit, (_, _, _) => UIO.unit, (_, _) => IO.unit)
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      cache       = CompositeViews.cache(config)
      views      <- CompositeViews(config, eventLog, orgs, projects, cache, agg, resolverCtx)
    } yield views

  def initViews(
      orgs: Organizations,
      projects: Projects,
      permissions: Set[Permission],
      aclCheck: AclCheck,
      client: ElasticSearchClient,
      crypto: Crypto,
      config: CompositeViewsConfig = CompositeViewsFixture.config
  )(implicit as: ActorSystem[Nothing], baseUri: BaseUri, uuidF: UUIDF, sc: Scheduler): Task[CompositeViews] =
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[CompositeViewEvent]](EventLogUtils.toEnvelope).hideErrors
      cache       = CompositeViews.cache(config)
      agg        <- CompositeViews.aggregate(
                      config,
                      projects,
                      aclCheck,
                      UIO.pure(permissions),
                      ResourceIdCheck.alwaysAvailable,
                      client,
                      deltaClient,
                      crypto
                    )
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      views      <- CompositeViews(config, eventLog, orgs, projects, cache, agg, resolverCtx)
    } yield views
}

object CompositeViewsSetup extends CompositeViewsSetup
