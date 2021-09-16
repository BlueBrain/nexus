package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.Fixtures.rcr
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ConfigFixtures, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Permissions, Projects, ResourceIdCheck}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import scala.concurrent.duration._

trait ElasticSearchViewsSetup extends IOValues with ConfigFixtures with IOFixedClock {

  private def config(implicit baseUri: BaseUri) = ElasticSearchViewsConfig(
    baseUri.toString,
    httpClientConfig,
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    10,
    1.minute,
    Refresh.False,
    2000
  )

  def init(
      org: Label,
      project: Project,
      perms: Permission*
  )(implicit
      api: JsonLdApi,
      base: BaseUri,
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      s: Subject,
      sc: Scheduler
  ): ElasticSearchViews = {
    for {
      (orgs, projs) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    } yield init(orgs, projs, perms: _*)
  }.accepted

  def init(
      orgs: Organizations,
      projects: Projects,
      perms: Permission*
  )(implicit api: JsonLdApi, base: BaseUri, as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): ElasticSearchViews =
    init(orgs, projects, PermissionsDummy(perms.toSet).accepted)

  def init(
      orgs: Organizations,
      projects: Projects,
      perms: Permissions
  )(implicit
      api: JsonLdApi,
      base: BaseUri,
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      sc: Scheduler
  ): ElasticSearchViews = {
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[ElasticSearchViewEvent]](EventLogUtils.toEnvelope).hideErrors
      deferred   <- Deferred[Task, ElasticSearchViews]
      cache      <- ElasticSearchViews.cache(config)
      agg        <- ElasticSearchViews.aggregate(config, perms, (_, _) => IO.unit, deferred, ResourceIdCheck.alwaysAvailable)
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      views      <- ElasticSearchViews(deferred, config, eventLog, resolverCtx, cache, agg, orgs, projects)
    } yield views
  }.accepted
}
object ElasticSearchViewsSetup extends ElasticSearchViewsSetup
