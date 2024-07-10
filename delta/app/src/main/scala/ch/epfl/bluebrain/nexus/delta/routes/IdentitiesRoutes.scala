package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.{Directive0, Route}
import cats.effect.IO
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning

/**
  * The identities routes
  */
class IdentitiesRoutes(identities: Identities, aclCheck: AclCheck, projectProvisioning: ProjectProvisioning)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck) {

  private def provisionProject(implicit caller: Caller): Directive0 = onSuccess(
    projectProvisioning(caller.subject).unsafeToFuture()
  )

  def routes: Route = {
    baseUriPrefix(baseUri.prefix) {
      (pathPrefix("identities") & pathEndOrSingleSlash) {
        (extractCaller & get) { implicit caller =>
          provisionProject.apply {
            emit(IO.pure(caller))
          }
        }
      }
    }
  }
}

object IdentitiesRoutes {

  /**
    * @return
    *   the [[Route]] for identities
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      projectProvisioning: ProjectProvisioning
  )(implicit baseUri: BaseUri, cr: RemoteContextResolution, ordering: JsonKeyOrdering): Route =
    new IdentitiesRoutes(identities, aclCheck, projectProvisioning).routes
}
