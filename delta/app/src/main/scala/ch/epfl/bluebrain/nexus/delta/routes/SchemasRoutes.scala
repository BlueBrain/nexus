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
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.schemas.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.SchemaNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The schemas routes
  *
  * @param identities    the identity module
  * @param acls          the ACLs module
  * @param organizations the organizations module
  * @param projects      the projects module
  * @param schemas       the schemas module
  */
final class SchemasRoutes(
    identities: Identities,
    acls: Acls,
    organizations: Organizations,
    projects: Projects,
    schemas: Schemas
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment

  implicit private val fetchProjectUuids: FetchUuids = projects

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.schemasMetadata))

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("schemas", shacl, projects)) {
      pathPrefix("schemas") {
        extractCaller { implicit caller =>
          concat(
            // SSE schemas for all events
            (pathPrefix("events") & pathEndOrSingleSlash) {
              get {
                operationName(s"$prefixSegment/schemas/events") {
                  authorizeFor(AclAddress.Root, events.read).apply {
                    lastEventId { offset =>
                      emit(schemas.events(offset))
                    }
                  }
                }
              }
            },
            // SSE schemas for all events belonging to an organization
            (orgLabel(organizations) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
              get {
                operationName(s"$prefixSegment/schemas/{org}/events") {
                  authorizeFor(org, events.read).apply {
                    lastEventId { offset =>
                      emit(schemas.events(org, offset).leftWiden[SchemaRejection])
                    }
                  }
                }
              }
            },
            projectRef(projects).apply { ref =>
              concat(
                // SSE schemas for all events belonging to a project
                (pathPrefix("events") & pathEndOrSingleSlash) {
                  get {
                    operationName(s"$prefixSegment/schemas/{org}/{project}/events") {
                      authorizeFor(ref, events.read).apply {
                        lastEventId { offset =>
                          emit(schemas.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                // Create a schema without id segment
                (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json]) & indexingType) {
                  (source, indexing) =>
                    operationName(s"$prefixSegment/schemas/{org}/{project}") {
                      authorizeFor(ref, Write).apply {
                        emit(Created, schemas.create(ref, source, indexing).map(_.void))
                      }
                    }
                },
                (idSegment & indexingType) { (id, indexing) =>
                  concat(
                    pathEndOrSingleSlash {
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}") {
                        concat(
                          // Create or update a schema
                          put {
                            authorizeFor(ref, Write).apply {
                              (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                case (None, source)      =>
                                  // Create a schema with id segment
                                  emit(Created, schemas.create(id, ref, source, indexing).map(_.void))
                                case (Some(rev), source) =>
                                  // Update a schema
                                  emit(schemas.update(id, ref, rev, source, indexing).map(_.void))
                              }
                            }
                          },
                          // Deprecate a schema
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeFor(ref, Write).apply {
                              emit(schemas.deprecate(id, ref, rev, indexing).map(_.void))
                            }
                          },
                          // Fetch a schema
                          (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                            emit(schemas.fetch(id, ref).leftWiden[SchemaRejection].rejectOn[SchemaNotFound])
                          }
                        )
                      }
                    },
                    // Fetch a schema original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/source") {
                        authorizeFor(ref, Read).apply {
                          val sourceIO = schemas.fetch(id, ref).map(_.value.source)
                          emit(sourceIO.leftWiden[SchemaRejection].rejectOn[SchemaNotFound])
                        }
                      }
                    },
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a schema tags
                          (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                            val tagsIO = schemas.fetch(id, ref).map(res => Tags(res.value.tags))
                            emit(tagsIO.leftWiden[SchemaRejection].rejectOn[SchemaNotFound])
                          },
                          // Tag a schema
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeFor(ref, Write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(Created, schemas.tag(id, ref, tag, tagRev, rev, indexing).map(_.void))
                              }
                            }
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
    * @return the [[Route]] for schemas
    */
  def apply(identities: Identities, acls: Acls, orgs: Organizations, projects: Projects, schemas: Schemas)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new SchemasRoutes(identities, acls, orgs, projects, schemas).routes

}
