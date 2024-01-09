package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SupervisionRoutes.{allProjectsAreHealthy, unhealthyProjectsEncoder, SupervisionBundle}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.{baseUriPrefix, projectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.directives._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.supervision
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ProjectHealer, ProjectsHealth}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisedDescription
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json}

class SupervisionRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    supervised: IO[List[SupervisedDescription]],
    projectsHealth: ProjectsHealth,
    projectHealer: ProjectHealer
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with RdfMarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("supervision") {
        extractCaller { implicit caller =>
          concat(
            authorizeFor(AclAddress.Root, supervision.read).apply {
              concat(
                (pathPrefix("projections") & get & pathEndOrSingleSlash) {
                  emit(supervised.map(SupervisionBundle))
                },
                (pathPrefix("projects") & get & pathEndOrSingleSlash) {
                  onSuccess(projectsHealth.health.unsafeToFuture()) { projects =>
                    if (projects.isEmpty) emit(StatusCodes.OK, IO.pure(allProjectsAreHealthy))
                    else emit(StatusCodes.InternalServerError, IO.pure(unhealthyProjectsEncoder(projects)))
                  }
                }
              )
            },
            // TODO: review permission
            authorizeFor(AclAddress.Root, supervision.write).apply {
              (post & pathPrefix("projects") & projectRef & pathPrefix("heal") & pathEndOrSingleSlash) { project =>
                emit(projectHealer.heal(project))
              }
            }
          )
        }
      }
    }

}

object SupervisionRoutes {

  case class SupervisionBundle(projections: List[SupervisedDescription])

  implicit final val runningProjectionsEncoder: Encoder[SupervisionBundle]       =
    deriveEncoder
  implicit val runningProjectionsJsonLdEncoder: JsonLdEncoder[SupervisionBundle] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.supervision))

  private val allProjectsAreHealthy                              =
    Json.obj("status" := "All projects are healthy.")

  private val unhealthyProjectsEncoder: Encoder[Set[ProjectRef]] =
    Encoder.instance { set =>
      Json.obj("status" := "Some projects are unhealthy.", "unhealthyProjects" := set)
    }

}
