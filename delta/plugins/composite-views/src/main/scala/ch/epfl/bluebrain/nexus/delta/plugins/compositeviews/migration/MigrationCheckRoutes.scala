package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import akka.http.scaladsl.model.StatusCodes.Accepted
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.MigrationCheckRoutes.logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.AccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import com.typesafe.scalalogging.Logger
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

class MigrationCheckRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    blazegraphCheck: BlazegraphViewsCheck,
    compositeCheck: CompositeViewsCheck,
    elasticCheck: ElasticSearchViewsCheck,
    projectStats: ProjectsStatsCheck,
    resourcesCheck: ResourcesCheck,
    serviceAccount: ServiceAccount
)(implicit baseUri: BaseUri, s: Scheduler)
    extends AuthDirectives(identities, aclCheck: AclCheck)
    with DeltaDirectives
    with CirceUnmarshalling
    with RdfMarshalling {

  private def startCheck(name: String, task: Task[Unit]) =
    task.doOnFinish {
      case Some(cause) =>
        UIO.delay(logger.error(s"Stopping check '$name' after error", cause.toThrowable))
      case None        =>
        UIO.delay(logger.info(s"Stopping stream '$name' after completion."))
    }.runAsyncAndForget

  def routes: Route =
    (baseUriPrefix(baseUri.prefix)) {
      pathPrefix("migration") {
        (extractCaller & extractToken) { case (caller, token) =>
          authorizeServiceAccount(serviceAccount)(caller) {
            concat(
              (pathPrefix("blazegraph") & post & pathEndOrSingleSlash) {
                startCheck("blazegraph", blazegraphCheck.run)
                complete(Accepted)
              },
              (pathPrefix("composite") & post & pathEndOrSingleSlash) {
                startCheck("composite", compositeCheck.run)
                complete(Accepted)
              },
              (pathPrefix("elasticsearch") & post & pathEndOrSingleSlash) {
                startCheck("elasticsearch", elasticCheck.run)
                complete(Accepted)
              },
              (pathPrefix("project-stats") & post & pathEndOrSingleSlash) {
                startCheck("project-stats", projectStats.run(AccessToken(token)))
                complete(Accepted)
              },
              (pathPrefix("resources") & post & pathEndOrSingleSlash) {
                startCheck("resources", resourcesCheck.run(AccessToken(token)))
                complete(Accepted)
              }
            )
          }
        }
      } delta_copy_to_test
    }

}

object MigrationCheckRoutes {
  private val logger: Logger = Logger[MigrationCheckRoutes]
}
