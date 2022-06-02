package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ConfigFixtures, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Permissions, Projects, ResourceIdCheck}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import scala.concurrent.duration._

trait BlazegraphViewsSetup extends IOValues with ConfigFixtures with IOFixedClock with Fixtures {

  def config(implicit baseUri: BaseUri) = BlazegraphViewsConfig(
    baseUri.toString,
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
      org: Label,
      project: Project,
      perms: Permission*
  )(implicit base: BaseUri, as: ActorSystem[Nothing], uuid: UUIDF, s: Subject, sc: Scheduler): BlazegraphViews = {
    for {
      (orgs, projs) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    } yield init(orgs, projs, perms: _*)
  }.accepted

  def init(
      orgs: Organizations,
      projects: Projects,
      perms: Permission*
  )(implicit base: BaseUri, as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): BlazegraphViews =
    init(orgs, projects, PermissionsDummy(perms.toSet).accepted)

  def init(
      orgs: Organizations,
      projects: Projects,
      perms: Permissions
  )(implicit base: BaseUri, as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): BlazegraphViews = {
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
      deferred   <- Deferred[Task, BlazegraphViews]
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      cache       = BlazegraphViews.cache(config)
      agg        <- BlazegraphViews.aggregate(config, deferred, perms, ResourceIdCheck.alwaysAvailable)
      views      <- BlazegraphViews(config, eventLog, resolverCtx, cache, deferred, agg, orgs, projects, _ => IO.unit)
    } yield views
  }.accepted
}

object BlazegraphViewsSetup extends BlazegraphViewsSetup
