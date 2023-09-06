package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

//import akka.http.scaladsl.server.Directives._
//import akka.http.scaladsl.server.Route
//import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolution
//import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultViewsQuery
//import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
//import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
//import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
//import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
//import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
//import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
//import monix.execution.Scheduler
//
//class IdResolutionRoutes(
//    identities: Identities,
//    aclCheck: AclCheck,
//    defaultViewsQuery: DefaultViewsQuery.Elasticsearch,
//    resources: Resources
//)(implicit
//    baseUri: BaseUri,
//    s: Scheduler
//) extends AuthDirectives(identities, aclCheck) {
//
//  def routes: Route =
//    baseUriPrefix(baseUri.prefix) {
//      pathPrefix("resolve") & iriSegment { iri =>
//        get & pathEndOrSingleSlash {
//          extractCaller { implicit caller =>
//            //emit(IdResolution.resolve(iri, defaultViewsQuery, resources.fetch))
//          }
//        }
//      }
//    }
//
//}
