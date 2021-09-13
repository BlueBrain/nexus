package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.quotas.{read => Read}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The quotas routes
  *
  * @param identities
  *   the identity module
  * @param acls
  *   the acls module
  * @param projects
  *   the projects module
  * @param quotas
  *   the quotas module
  */
final class QuotasRoutes(
    identities: Identities,
    acls: Acls,
    projects: Projects,
    quotas: Quotas
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, acls)
    with RdfMarshalling {

  import baseUri.prefixSegment

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & pathPrefix("quotas")) {
      extractCaller { implicit caller =>
        projectRef(projects).apply { implicit ref =>
          (pathEndOrSingleSlash & operationName(s"$prefixSegment/quotas/{org}/{project}")) {
            // Get quotas for a project
            (get & authorizeFor(ref, Read)) {
              emit(quotas.fetch(ref))
            }
          }
        }
      }
    }
}
