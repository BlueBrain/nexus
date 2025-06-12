package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.{Directive, Route}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.mainIndexingProjection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.read as Read
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewRejection, defaultViewId, permissions}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.MainIndexQuery
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives.extractQueryParams
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

final class MainIndexRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    defaultIndexQuery: MainIndexQuery,
    projections: Projections
)(implicit cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics] =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))

  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.statistics))

  private def defaultViewSegment: Directive[Unit] =
    idSegment.flatMap {
      case IdSegment.StringSegment(string) if string == "documents" => tprovide(())
      case IdSegment.IriSegment(iri) if iri == defaultViewId        => tprovide(())
      case _                                                        => reject()
    }

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        projectRef { project =>
          defaultViewSegment {
            concat(
              // Fetch statistics for the default indexing on this current project
              (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                authorizeFor(project, Read).apply {
                  val projection = mainIndexingProjection(project)
                  emit(projections.statistics(project, SelectFilter.latest, projection))
                }
              },
              // Query default indexing for this given project
              (pathPrefix("_search") & post & pathEndOrSingleSlash) {
                authorizeFor(project, permissions.query).apply {
                  (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                    emit(defaultIndexQuery.search(project, query, qp).attemptNarrow[ElasticSearchViewRejection])
                  }
                }
              }
            )
          }
        }
      }
    }
}
