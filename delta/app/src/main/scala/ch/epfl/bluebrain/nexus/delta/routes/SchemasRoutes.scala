package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas.shacl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.schemas.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.SchemaNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{Schema, SchemaRejection}
import io.circe.{Json, Printer}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._

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
  * @param index
  *   the indexing action on write operations
  */
final class SchemasRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    schemas: Schemas,
    schemeDirectives: DeltaSchemeDirectives,
    index: IndexingAction.Execute[Schema]
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment
  import schemeDirectives._

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.schemasMetadata))

  private def triggerIndexing(io: IO[SchemaResource], indexingMode: IndexingMode) =
    io.toBIO[SchemaRejection].tapEval { schema => index(schema.value.project, schema, indexingMode) }.map(_.void)

  private def emitIndex(status: StatusCode, io: IO[SchemaResource], indexingMode: IndexingMode): Route =
    emit(status, triggerIndexing(io, indexingMode))

  private def emitIndex(io: IO[SchemaResource], indexingMode: IndexingMode): Route = emitIndex(OK, io, indexingMode)

  private def emitIndexRejectOnNotFound(io: IO[SchemaResource], indexingMode: IndexingMode): Route =
    emit(triggerIndexing(io, indexingMode).rejectOn[SchemaNotFound])

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("schemas", shacl)) {
      pathPrefix("schemas") {
        extractCaller { implicit caller =>
          resolveProjectRef.apply { ref =>
            concat(
              // Create a schema without id segment
              (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                operationName(s"$prefixSegment/schemas/{org}/{project}") {
                  authorizeFor(ref, Write).apply {
                    emitIndex(Created, schemas.create(ref, source), mode)
                  }
                }
              },
              (idSegment & indexingMode) { (id, mode) =>
                concat(
                  pathEndOrSingleSlash {
                    operationName(s"$prefixSegment/schemas/{org}/{project}/{id}") {
                      concat(
                        // Create or update a schema
                        put {
                          authorizeFor(ref, Write).apply {
                            (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                              case (None, source)      =>
                                // Create a schema with id segment
                                emitIndex(Created, schemas.create(id, ref, source), mode)
                              case (Some(rev), source) =>
                                // Update a schema
                                emitIndex(schemas.update(id, ref, rev, source), mode)
                            }
                          }
                        },
                        // Deprecate a schema
                        (delete & parameter("rev".as[Int])) { rev =>
                          authorizeFor(ref, Write).apply {
                            emitIndexRejectOnNotFound(schemas.deprecate(id, ref, rev), mode)
                          }
                        },
                        // Fetch a schema
                        (get & idSegmentRef(id)) { id =>
                          emitOrFusionRedirect(
                            ref,
                            id,
                            authorizeFor(ref, Read).apply {
                              emit(schemas.fetch(id, ref).toBIO[SchemaRejection].rejectOn[SchemaNotFound])
                            }
                          )
                        }
                      )
                    }
                  },
                  (pathPrefix("refresh") & put & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/refresh") {
                      authorizeFor(ref, Write).apply {
                        emit(
                          OK,
                          schemas.refresh(id, ref).toBIO[SchemaRejection].tapEval(index(ref, _, mode)).map(_.void)
                        )
                      }
                    }
                  },
                  // Fetch a schema original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/source") {
                      authorizeFor(ref, Read).apply {
                        implicit val source: Printer = sourcePrinter
                        val sourceIO                 = schemas.fetch(id, ref).map(_.value.source)
                        emit(sourceIO.toBIO[SchemaRejection].rejectOn[SchemaNotFound])
                      }
                    }
                  },
                  pathPrefix("tags") {
                    operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/tags") {
                      concat(
                        // Fetch a schema tags
                        (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(ref, Read)) { id =>
                          val tagsIO = schemas.fetch(id, ref).map(_.value.tags)
                          emit(tagsIO.toBIO[SchemaRejection].rejectOn[SchemaNotFound])
                        },
                        // Tag a schema
                        (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                          authorizeFor(ref, Write).apply {
                            entity(as[Tag]) { case Tag(tagRev, tag) =>
                              emit(
                                Created,
                                schemas
                                  .tag(id, ref, tag, tagRev, rev)
                                  .toBIO[SchemaRejection]
                                  .tapEval(index(ref, _, mode))
                                  .map(_.void)
                              )
                            }
                          }
                        },
                        // Delete a tag
                        (tagLabel & delete & parameter("rev".as[Int]) & pathEndOrSingleSlash & authorizeFor(
                          ref,
                          Write
                        )) { (tag, rev) =>
                          emitIndexRejectOnNotFound(schemas.deleteTag(id, ref, tag, rev), mode)
                        }
                      )
                    }
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
      schemeDirectives: DeltaSchemeDirectives,
      index: IndexingAction.Execute[Schema]
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = new SchemasRoutes(identities, aclCheck, schemas, schemeDirectives, index).routes

}
