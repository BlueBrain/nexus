package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.instances.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.job.SchemaValidationCoordinator
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.utils.StreamingUtils

import java.nio.ByteBuffer

/**
  * Routes to trigger and get results from a schema validation job
  */
class SchemaJobRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    fetchContext: FetchContext,
    schemaValidationCoordinator: SchemaValidationCoordinator,
    projections: Projections,
    projectionsErrors: ProjectionErrors
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck) {

  private def projectionName(project: ProjectRef) = SchemaValidationCoordinator.projectionMetadata(project).name

  private def projectExists(project: ProjectRef) = fetchContext.onRead(project).void

  private def streamValidationErrors(project: ProjectRef): IO[FileResponse] = IO.pure {
    val errors = projectionsErrors
      .failedElemEntries(projectionName(project), Offset.start)
      .map(_.failedElemData)
      .through(StreamingUtils.ndjson)
      .map { s => ByteBuffer.wrap(s.getBytes) }
    FileResponse.unsafe("validation.json", ContentTypes.`application/json`, None, None, errors)
  }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("jobs") {
        extractCaller { implicit caller =>
          pathPrefix("schemas") {
            (pathPrefix("validation") & projectRef) { project =>
              authorizeFor(project, Permissions.schemas.run).apply {
                concat(
                  (post & pathEndOrSingleSlash) {
                    emit(
                      StatusCodes.Accepted,
                      projectExists(project) >> schemaValidationCoordinator.run(project).start.void
                    )
                  },
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    emit(
                      projectExists(project) >> projections
                        .statistics(
                          project,
                          SelectFilter.latest,
                          projectionName(project)
                        )
                    )
                  },
                  (pathPrefix("errors") & get & pathEndOrSingleSlash) {
                    emit(
                      projectExists(project) >> streamValidationErrors(project).attemptNarrow[ResourceRejection]
                    )
                  }
                )
              }
            }
          }
        }
      }
    }
}
