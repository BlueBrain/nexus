package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{ElasticSearchQueryError, MainIndexQuery, MainIndexRequest}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.AggregationResult.aggregationResultJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectScopeResolver
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.JsonObject

class ListingRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    projectScopeResolver: ProjectScopeResolver,
    resourcesToSchemas: ResourceToSchemaMappings,
    schemeDirectives: DeltaSchemeDirectives,
    defaultIndexQuery: MainIndexQuery
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with ElasticSearchViewsDirectives {

  import schemeDirectives._

  def routes: Route = concat(genericResourcesRoutes, resourcesListings)

  private val genericResourcesRoutes: Route =
    pathPrefix("resources") {
      extractCaller { implicit caller =>
        concat(
          (searchParametersAndSortList & paginated) { (params, sort, page) =>
            val request = MainIndexRequest(params, page, sort)
            concat(
              // List/aggregate all resources
              pathEndOrSingleSlash {
                concat(
                  aggregate(request, Scope.Root),
                  list(request, Scope.Root)
                )
              },
              (label & pathEndOrSingleSlash) { org =>
                val scope = Scope.Org(org)
                concat(
                  aggregate(request, scope),
                  list(request, scope)
                )
              }
            )
          },
          projectRef { project =>
            projectContext(project) { implicit pc =>
              (get & searchParametersInProject & paginated) { (params, sort, page) =>
                val scope = Scope.Project(project)
                concat(
                  // List/aggregate all resources inside a project
                  pathEndOrSingleSlash {
                    val request = MainIndexRequest(params, page, sort)
                    concat(
                      aggregate(request, scope),
                      list(request, scope)
                    )
                  },
                  idSegment { schema =>
                    // List/aggregate all resources inside a project filtering by its schema type
                    pathEndOrSingleSlash {
                      underscoreToOption(schema) match {
                        case None                =>
                          val request = MainIndexRequest(params, page, sort)
                          concat(
                            aggregate(request, scope),
                            list(request, scope)
                          )
                        case Some(schemaSegment) =>
                          resourceRef(schemaSegment).apply { schemaRef =>
                            val request =
                              MainIndexRequest(params.withSchema(schemaRef), page, sort)
                            concat(
                              aggregate(request, scope),
                              list(request, scope)
                            )
                          }
                      }
                    }
                  }
                )
              }
            }
          }
        )
      }
    }

  private val resourcesListings: Route =
    concat(resourcesToSchemas.value.map { case (Label(resourceSegment), resourceSchema) =>
      pathPrefix(resourceSegment) {
        extractCaller { implicit caller =>
          concat(
            (searchParametersAndSortList & paginated) { (params, sort, page) =>
              val request = MainIndexRequest(params.withSchema(resourceSchema), page, sort)
              concat(
                // List all resources of type resourceSegment
                pathEndOrSingleSlash {
                  concat(
                    aggregate(request, Scope.Root),
                    list(request, Scope.Root)
                  )
                },
                // List all resources of type resourceSegment inside an organization
                (label & pathEndOrSingleSlash) { org =>
                  val scope = Scope.Org(org)
                  concat(
                    aggregate(request, scope),
                    list(request, scope)
                  )
                }
              )
            },
            projectRef { project =>
              projectContext(project) { implicit pc =>
                // List all resources of type resourceSegment inside a project
                (searchParametersInProject & paginated & pathEndOrSingleSlash) { (params, sort, page) =>
                  val request = MainIndexRequest(params.withSchema(resourceSchema), page, sort)
                  val scope   = Scope.Project(project)
                  concat(
                    aggregate(request, scope),
                    list(request, scope)
                  )
                }
              }
            }
          )
        }
      }
    }.toSeq: _*)

  private def list(request: MainIndexRequest, scope: Scope)(implicit caller: Caller): Route =
    (get & paginated & extractUri) { (page, uri) =>
      implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
        searchResultsJsonLdEncoder(ContextValue(contexts.searchMetadata), page, uri)
      emit {
        projectScopeResolver(scope, resources.read).flatMap { projects =>
          defaultIndexQuery.list(request, projects).attemptNarrow[ElasticSearchQueryError]
        }
      }
    }

  private def aggregate(request: MainIndexRequest, scope: Scope)(implicit caller: Caller): Route =
    (get & aggregated) {
      implicit val searchJsonLdEncoder: JsonLdEncoder[AggregationResult] =
        aggregationResultJsonLdEncoder(ContextValue(contexts.aggregations))

      implicit val reultHttpResponseFields: HttpResponseFields[AggregationResult] = HttpResponseFields.defaultOk

      emit {
        projectScopeResolver(scope, resources.read).flatMap { projects =>
          defaultIndexQuery.aggregate(request, projects).attemptNarrow[ElasticSearchQueryError]
        }
      }

    }

}
