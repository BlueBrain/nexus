package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas.shacl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.schemas.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.SchemaNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{SchemaEvent, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseConverter
import io.circe.{Json, Printer}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

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
    index: IndexingAction
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

  implicit val sseConverter: SseConverter[SchemaEvent] = SseConverter(SchemaEvent.sseEncoder)

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.schemasMetadata))

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("schemas", shacl)) {
      pathPrefix("schemas") {
        extractCaller { implicit caller =>
          concat(
            // SSE schemas for all events
            (pathPrefix("events") & pathEndOrSingleSlash) {
              get {
                operationName(s"$prefixSegment/schemas/events") {
                  authorizeFor(AclAddress.Root, events.read).apply {
                    lastEventIdNew { offset =>
                      emit(schemas.events(offset))
                    }
                  }
                }
              }
            },
            // SSE schemas for all events belonging to an organization
            (resolveOrg & pathPrefix("events") & pathEndOrSingleSlash) { org =>
              get {
                operationName(s"$prefixSegment/schemas/{org}/events") {
                  authorizeFor(org, events.read).apply {
                    lastEventIdNew { offset =>
                      emit(schemas.events(org, offset).leftWiden[SchemaRejection])
                    }
                  }
                }
              }
            },
            resolveProjectRef.apply { ref =>
              concat(
                // SSE schemas for all events belonging to a project
                (pathPrefix("events") & pathEndOrSingleSlash) {
                  get {
                    operationName(s"$prefixSegment/schemas/{org}/{project}/events") {
                      authorizeFor(ref, events.read).apply {
                        lastEventIdNew { offset =>
                          emit(schemas.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                // Create a schema without id segment
                (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                  operationName(s"$prefixSegment/schemas/{org}/{project}") {
                    authorizeFor(ref, Write).apply {
                      emit(Created, schemas.create(ref, source).tapEval(index(ref, _, mode)).map(_.void))
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
                                  emit(
                                    Created,
                                    schemas.create(id, ref, source).tapEval(index(ref, _, mode)).map(_.void)
                                  )
                                case (Some(rev), source) =>
                                  // Update a schema
                                  emit(schemas.update(id, ref, rev, source).tapEval(index(ref, _, mode)).map(_.void))
                              }
                            }
                          },
                          // Deprecate a schema
                          (delete & parameter("rev".as[Int])) { rev =>
                            authorizeFor(ref, Write).apply {
                              emit(
                                schemas
                                  .deprecate(id, ref, rev)
                                  .tapEval(index(ref, _, mode))
                                  .map(_.void)
                                  .rejectOn[SchemaNotFound]
                              )
                            }
                          },
                          // Fetch a schema
                          (get & idSegmentRef(id)) { id =>
                            emitOrFusionRedirect(
                              ref,
                              id,
                              authorizeFor(ref, Read).apply {
                                emit(schemas.fetch(id, ref).leftWiden[SchemaRejection].rejectOn[SchemaNotFound])
                              }
                            )
                          }
                        )
                      }
                    },
                    // Fetch a schema original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/source") {
                        authorizeFor(ref, Read).apply {
                          implicit val source: Printer = sourcePrinter
                          val sourceIO                 = schemas.fetch(id, ref).map(_.value.source)
                          emit(sourceIO.leftWiden[SchemaRejection].rejectOn[SchemaNotFound])
                        }
                      }
                    },
                    pathPrefix("tags") {
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a schema tags
                          (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(ref, Read)) { id =>
                            val tagsIO = schemas.fetch(id, ref).map(_.value.tags)
                            emit(tagsIO.leftWiden[SchemaRejection].rejectOn[SchemaNotFound])
                          },
                          // Tag a schema
                          (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                            authorizeFor(ref, Write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(
                                  Created,
                                  schemas.tag(id, ref, tag, tagRev.toInt, rev).tapEval(index(ref, _, mode)).map(_.void)
                                )
                              }
                            }
                          },
                          // Delete a tag
                          (tagLabel & delete & parameter("rev".as[Int]) & pathEndOrSingleSlash & authorizeFor(
                            ref,
                            Write
                          )) { (tag, rev) =>
                            emit(schemas.deleteTag(id, ref, tag, rev).tapEval(index(ref, _, mode)).map(_.void))
                          }
                        )
                      }
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
      index: IndexingAction
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = new SchemasRoutes(identities, aclCheck, schemas, schemeDirectives, index).routes

}
