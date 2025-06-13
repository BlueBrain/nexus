package ai.senscience.nexus.delta.projectdeletion

import ai.senscience.nexus.delta.projectdeletion.model.ProjectDeletionConfig
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri

/**
  * The project deletion routes that expose the current configuration of the plugin.
  *
  * @param config
  *   the automatic project deletion configuration
  * @param baseUri
  *   the system base uri
  */
class ProjectDeletionRoutes(config: ProjectDeletionConfig)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends RdfMarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("project-deletion" / "config") {
        emit(IO.pure(config))
      }
    }

}
