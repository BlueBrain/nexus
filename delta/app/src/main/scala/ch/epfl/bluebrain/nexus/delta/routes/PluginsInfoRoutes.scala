package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.OnePage
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginInfo
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

class PluginsInfoRoutes(identities: Identities, acls: Acls, info: List[PluginInfo])(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  implicit private val context: ContextValue                           = ContextValue(contexts.pluginsInfo)
  implicit private val pluginInfoEncoder: Encoder.AsObject[PluginInfo] = deriveEncoder[PluginInfo]

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("plugins") {
        extractCaller { implicit caller =>
          // List of plugins
          (get & extractUri & pathEndOrSingleSlash) { uri =>
            operationName(s"$prefixSegment/plugins") {
              authorizeFor(AclAddress.Root, plugins.read).apply {
                implicit val searchEncoder: SearchEncoder[PluginInfo] = searchResultsEncoder(OnePage, uri)
                emit(UIO.pure(SearchResults(info.size.toLong, info)))
              }
            }
          }
        }
      }
    }

}

object PluginsInfoRoutes {

  final def apply(identities: Identities, acls: Acls, info: List[PluginInfo])(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new PluginsInfoRoutes(identities, acls, info).routes
}
