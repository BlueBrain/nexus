package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.*
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas.shacl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{OriginalSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.schemas.{read as Read, write as Write}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.SchemaNotFound
import io.circe.Json

/**
  * The schemas routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   verify the acls for users
  * @param schemas
  *   the schemas module
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
final class SchemasRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    schemas: Schemas,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives.*

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.schemasMetadata))

  private def emitFetch(io: IO[SchemaResource]): Route =
    emit(io.attemptNarrow[SchemaRejection].rejectOn[SchemaNotFound])

  private def emitMetadata(statusCode: StatusCode, io: IO[SchemaResource]): Route =
    emit(statusCode, io.map(_.void).attemptNarrow[SchemaRejection])

  private def emitMetadata(io: IO[SchemaResource]): Route = emitMetadata(StatusCodes.OK, io)

  private def emitMetadataOrReject(io: IO[SchemaResource]): Route =
    emit(io.map(_.void).attemptNarrow[SchemaRejection].rejectOn[SchemaNotFound])

  private def emitSource(io: IO[SchemaResource], annotate: Boolean): Route =
    emit(
      io.map { resource => OriginalSource(resource, resource.value.source, annotate) }
        .attemptNarrow[SchemaRejection]
        .rejectOn[SchemaNotFound]
    )

  private def emitTags(io: IO[SchemaResource]): Route =
    emit(io.map(_.value.tags).attemptNarrow[SchemaRejection].rejectOn[SchemaNotFound])

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("schemas", shacl)) {
      pathPrefix("schemas") {
        extractCaller { implicit caller =>
          projectRef { project =>
            val authorizeRead  = authorizeFor(project, Read)
            val authorizeWrite = authorizeFor(project, Write)
            concat(
              // List schemas
              pathEndOrSingleSlash {
                (get & authorizeRead) {
                  implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ResourceF[Unit]]] =
                    searchResultsJsonLdEncoder(ContextValue.empty)
                  emit(schemas.list(project).map(_.map(_.void)).widen[SearchResults[ResourceF[Unit]]])
                }
              },
              // Create a schema without id segment
              (pathEndOrSingleSlash & post & noParameter("rev") & entity(as[Json])) { source =>
                authorizeWrite {
                  emitMetadata(Created, schemas.create(project, source))
                }
              },
              idSegment { id =>
                concat(
                  pathEndOrSingleSlash {
                    concat(
                      // Create or update a schema
                      put {
                        authorizeWrite {
                          (parameter("rev".as[Int].?) & entity(as[Json])) {
                            case (None, source)      =>
                              // Create a schema with id segment
                              emitMetadata(Created, schemas.create(id, project, source))
                            case (Some(rev), source) =>
                              // Update a schema
                              emitMetadata(schemas.update(id, project, rev, source))
                          }
                        }
                      },
                      // Deprecate a schema
                      (delete & parameter("rev".as[Int])) { rev =>
                        authorizeWrite {
                          emitMetadataOrReject(schemas.deprecate(id, project, rev))
                        }
                      },
                      // Fetch a schema
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(
                          project,
                          id,
                          authorizeRead {
                            emitFetch(schemas.fetch(id, project))
                          }
                        )
                      }
                    )
                  },
                  (pathPrefix("undeprecate") & put & pathEndOrSingleSlash & parameter("rev".as[Int])) { rev =>
                    authorizeWrite {
                      emitMetadataOrReject(schemas.undeprecate(id, project, rev))
                    }
                  },
                  (pathPrefix("refresh") & put & pathEndOrSingleSlash) {
                    authorizeWrite {
                      emitMetadata(schemas.refresh(id, project))
                    }
                  },
                  // Fetch a schema original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id) & annotateSource) {
                    (id, annotate) =>
                      authorizeRead {
                        emitSource(schemas.fetch(id, project), annotate)
                      }
                  },
                  pathPrefix("tags") {
                    concat(
                      // Fetch a schema tags
                      (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeRead) { id =>
                        emitTags(schemas.fetch(id, project))
                      },
                      // Tag a schema
                      (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                        authorizeWrite {
                          entity(as[Tag]) { case Tag(tagRev, tag) =>
                            emitMetadata(Created, schemas.tag(id, project, tag, tagRev, rev))
                          }
                        }
                      },
                      // Delete a tag
                      (tagLabel & delete & parameter("rev".as[Int]) & pathEndOrSingleSlash & authorizeWrite) {
                        (tag, rev) => emitMetadataOrReject(schemas.deleteTag(id, project, tag, rev))
                      }
                    )
                  }
                )
              }
            )
          }
        }
      }
    }
}

object SchemasRoutes {

  /**
    * @return
    *   the [[Route]] for schemas
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      schemas: Schemas,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = new SchemasRoutes(identities, aclCheck, schemas, schemeDirectives).routes

}
