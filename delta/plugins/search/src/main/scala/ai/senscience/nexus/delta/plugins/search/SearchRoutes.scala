package ai.senscience.nexus.delta.plugins.search

import ai.senscience.nexus.delta.plugins.search.model.SearchConfig.NamedSuite
import ai.senscience.nexus.delta.plugins.search.model.SearchRejection.UnknownSuite
import ai.senscience.nexus.delta.plugins.search.model.{SearchConfig, SearchRejection}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives.extractQueryParams
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName

class SearchRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    search: Search,
    configFields: Json,
    suites: SearchConfig.Suites
)(implicit baseUri: BaseUri, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling
    with DeltaDirectives {

  import baseUri.prefixSegment

  private val addProjectParam = "addProject"

  private def additionalProjects = parameter(addProjectParam.as[ProjectRef].*)

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("search") {
        extractCaller { implicit caller =>
          concat(
            // Query the underlying aggregate elasticsearch view for global search
            (pathPrefix("query") & post) {
              (extractQueryParams & entity(as[JsonObject])) { (qp, payload) =>
                concat(
                  pathEndOrSingleSlash {
                    emit(search.query(payload, qp).attemptNarrow[SearchRejection])
                  },
                  (pathPrefix("suite") & label & additionalProjects & pathEndOrSingleSlash) {
                    (suite, additionalProjects) =>
                      val filteredQp = qp.filterNot { case (key, _) => key == addProjectParam }
                      emit(
                        search
                          .query(suite, additionalProjects.toSet, payload, filteredQp)
                          .attemptNarrow[SearchRejection]
                      )
                  }
                )
              }
            },
            // Get fields config
            (pathPrefix("config") & get & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/search/config") {
                emit(IO.pure(configFields))
              }
            },
            // Fetch suite
            (pathPrefix("suites") & get & label & pathEndOrSingleSlash) { suiteName =>
              emit(
                IO.fromOption(suites.get(suiteName))(UnknownSuite(suiteName))
                  .map(s => NamedSuite(suiteName, s))
                  .attemptNarrow[SearchRejection]
              )
            }
          )

        }
      }
    }
}
