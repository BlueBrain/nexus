package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, schemas => schemaPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
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
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("schemas") {
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
                  authorizeFor(AclAddress.Organization(org), events.read).apply {
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
                      authorizeFor(AclAddress.Project(ref), events.read).apply {
                        lastEventId { offset =>
                          emit(schemas.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                // Create a schema without id segment
                (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json])) { source =>
                  operationName(s"$prefixSegment/schemas/{org}/{project}") {
                    authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
                      emit(Created, schemas.create(ref, source).map(_.void))
                    }
                  }
                },
                idSegment { id =>
                  concat(
                    pathEndOrSingleSlash {
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}") {
                        concat(
                          // Create or update a schema
                          put {
                            authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
                              (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                case (None, source)      =>
                                  // Create a schema with id segment
                                  emit(Created, schemas.create(id, ref, source).map(_.void))
                                case (Some(rev), source) =>
                                  // Update a schema
                                  emit(schemas.update(id, ref, rev, source).map(_.void))
                              }
                            }
                          },
                          // Deprecate a schema
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
                              emit(schemas.deprecate(id, ref, rev).map(_.void))
                            }
                          },
                          // Fetch a schema
                          get {
                            fetch(id, ref)
                          }
                        )
                      }
                    },
                    // Fetch a schema original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/source") {
                        fetchMap(id, ref, resource => JsonSource(resource.value.source, resource.value.id))
                      }
                    },
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a schema tags
                          get {
                            fetchMap(id, ref, resource => Tags(resource.value.tags))
                          },
                          // Tag a schema
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(Created, schemas.tag(id, ref, tag, tagRev, rev).map(_.void))
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

  private def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    fetchMap(id, ref, identity)

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      f: SchemaResource => A
  )(implicit caller: Caller) =
    authorizeFor(AclAddress.Project(ref), schemaPermissions.read).apply {
      (parameter("rev".as[Long].?) & parameter("tag".as[TagLabel].?)) {
        case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
        case (Some(rev), _)     => emit(schemas.fetchAt(id, ref, rev).leftWiden[SchemaRejection].map(f))
        case (_, Some(tag))     => emit(schemas.fetchBy(id, ref, tag).leftWiden[SchemaRejection].map(f))
        case _                  => emit(schemas.fetch(id, ref).leftWiden[SchemaRejection].map(f))
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
