package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.routes.SupervisionRoutes.{allProjectsAreHealthy, healingSuccessfulResponse, unhealthyProjectsEncoder, SupervisionBundle}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.{baseUriPrefix, projectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{HttpResponseFields, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{projects, supervision}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectHealer, ProjectsHealth}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ProjectActivitySignals, SupervisedDescription}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Encoder, Json}

class SupervisionRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    supervised: IO[List[SupervisedDescription]],
    projectsHealth: ProjectsHealth,
    projectHealer: ProjectHealer,
    activitySignals: ProjectActivitySignals
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
                },
                (pathPrefix("activity") & pathPrefix("projects") & get & pathEndOrSingleSlash) {
                  emit(activitySignals.activityMap.map(_.asJson))
                }
              )
            },
            authorizeFor(AclAddress.Root, projects.write).apply {
              (post & pathPrefix("projects") & projectRef & pathPrefix("heal") & pathEndOrSingleSlash) { project =>
                emit(
                  projectHealer
                    .heal(project)
                    .map(_ => healingSuccessfulResponse(project))
                    .attemptNarrow[ProjectRejection]
                )
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

  implicit val versionHttpResponseFields: HttpResponseFields[SupervisionBundle] = HttpResponseFields.defaultOk

  private val allProjectsAreHealthy =
    Json.obj("status" := "All projects are healthy.")

  private val unhealthyProjectsEncoder: Encoder[Set[ProjectRef]] =
    Encoder.instance { set =>
      Json.obj("status" := "Some projects are unhealthy.", "unhealthyProjects" := set)
    }

  private def healingSuccessfulResponse(project: ProjectRef) =
    Json.obj("message" := s"Project '$project' has been healed.")

}
