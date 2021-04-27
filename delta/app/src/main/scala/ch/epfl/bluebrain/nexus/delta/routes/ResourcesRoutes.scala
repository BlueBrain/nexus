package ch.epfl.bluebrain.nexus.delta.routes

/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 140
}
 */

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, resources => resourcePermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/** The resource routes
  *
  * @param identities    the identity module
  * @param acls          the ACLs module
  * @param organizations the organizations module
  * @param projects      the projects module
  * @param resources     the resources module
  * @param sseEventLog   the global eventLog of all events
  */
final class ResourcesRoutes(
    identities: Identities,
    acls: Acls,
    organizations: Organizations,
    projects: Projects,
    resources: Resources,
    sseEventLog: SseEventLog
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment

  implicit private val fetchProjectUuids: FetchUuids = projects

  private val resourceSchema = schemas.resources

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue.empty)

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("resources") {
          concat(
            // SSE resources for all events
            (pathPrefix("events") & pathEndOrSingleSlash) {
              get {
                operationName(s"$prefixSegment/resources/events") {
                  authorizeFor(AclAddress.Root, events.read).apply {
                    lastEventId { offset =>
                      emit(sseEventLog.stream(offset))
                    }
                  }
                }
              }
            },
            // SSE resources for all events belonging to an organization
            (orgLabel(organizations) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
              get {
                operationName(s"$prefixSegment/resources/{org}/events") {
                  authorizeFor(org, events.read).apply {
                    lastEventId { offset =>
                      emit(sseEventLog.stream(org, offset).leftWiden[ResourceRejection])
                    }
                  }
                }
              }
            },
            projectRef(projects).apply { ref =>
              concat(
                // SSE resources for all events belonging to a project
                (pathPrefix("events") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/resources/{org}/{project}/events") {
                    concat(
                      get {
                        authorizeFor(ref, events.read).apply {
                          lastEventId { offset =>
                            emit(sseEventLog.stream(ref, offset).leftWiden[ResourceRejection])
                          }
                        }
                      },
                      (head & authorizeFor(ref, events.read)) {
                        complete(OK)
                      }
                    )
                  }
                },
                // Create a resource without schema nor id segment
                (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json])) { source =>
                  operationName(s"$prefixSegment/resources/{org}/{project}") {
                    authorizeFor(ref, resourcePermissions.write).apply {
                      emit(Created, resources.create(ref, resourceSchema, source).map(_.void))
                    }
                  }
                },
                idSegment { schema =>
                  val schemaOpt = underscoreToOption(schema)
                  concat(
                    // Create a resource with schema but without id segment
                    (post & pathEndOrSingleSlash & noParameter("rev")) {
                      operationName(s"$prefixSegment/resources/{org}/{project}/{schema}") {
                        authorizeFor(ref, resourcePermissions.write).apply {
                          entity(as[Json]) { source =>
                            emit(
                              Created,
                              resources.create(ref, schema, source).map(_.void).rejectWhen(wrongJsonOrNotFound)
                            )
                          }
                        }
                      }
                    },
                    idSegment { id =>
                      concat(
                        pathEndOrSingleSlash {
                          operationName(s"$prefixSegment/resources/{org}/{project}/{schema}/{id}") {
                            concat(
                              // Create or update a resource
                              put {
                                authorizeFor(ref, resourcePermissions.write).apply {
                                  (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                    case (None, source)      =>
                                      // Create a resource with schema and id segments
                                      emit(
                                        Created,
                                        resources.create(id, ref, schema, source).map(_.void).rejectWhen(wrongJsonOrNotFound)
                                      )
                                    case (Some(rev), source) =>
                                      // Update a resource
                                      emit(
                                        resources
                                          .update(id, ref, schemaOpt, rev, source)
                                          .map(_.void)
                                          .rejectWhen(wrongJsonOrNotFound)
                                      )
                                  }
                                }
                              },
                              // Deprecate a resource
                              (delete & parameter("rev".as[Long])) { rev =>
                                authorizeFor(ref, resourcePermissions.write).apply {
                                  emit(
                                    resources.deprecate(id, ref, schemaOpt, rev).map(_.void).rejectWhen(wrongJsonOrNotFound)
                                  )
                                }
                              },
                              // Fetch a resource
                              get {
                                fetch(id, ref, schemaOpt)
                              }
                            )
                          }
                        },
                        // Fetch a resource original source
                        (pathPrefix("source") & get & pathEndOrSingleSlash) {
                          operationName(s"$prefixSegment/resources/{org}/{project}/{schema}/{id}/source") {
                            fetchSource(id, ref, schemaOpt, _.value.source)
                          }
                        },
                        // Tag a resource
                        (pathPrefix("tags") & pathEndOrSingleSlash) {
                          operationName(s"$prefixSegment/resources/{org}/{project}/{schema}/{id}/tags") {
                            concat(
                              // Fetch a resource tags
                              get {
                                fetchMap(id, ref, schemaOpt, res => Tags(res.value.tags))
                              },
                              // Tag a resource
                              (post & parameter("rev".as[Long])) { rev =>
                                authorizeFor(ref, resourcePermissions.write).apply {
                                  entity(as[Tag]) { case Tag(tagRev, tag) =>
                                    emit(
                                      Created,
                                      resources.tag(id, ref, schemaOpt, tag, tagRev, rev).map(_.void).rejectWhen(wrongJsonOrNotFound)
                                    )
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
          )
        }
      }
    }

  private def fetch(
      id: IdSegment,
      ref: ProjectRef,
      schemaOpt: Option[IdSegment]
  )(implicit caller: Caller) =
    fetchMap(id, ref, schemaOpt, identity)

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      schemaOpt: Option[IdSegment],
      f: DataResource => A
  )(implicit caller: Caller) =
    authorizeFor(ref, resourcePermissions.read).apply {
      fetchResource(
        rev => emit(resources.fetchAt(id, ref, schemaOpt, rev).leftWiden[ResourceRejection].map(f).rejectWhen(wrongJsonOrNotFound)),
        tag => emit(resources.fetchBy(id, ref, schemaOpt, tag).leftWiden[ResourceRejection].map(f).rejectWhen(wrongJsonOrNotFound)),
        emit(resources.fetch(id, ref, schemaOpt).leftWiden[ResourceRejection].map(f).rejectWhen(wrongJsonOrNotFound))
      )
    }

  private def fetchSource(
      id: IdSegment,
      ref: ProjectRef,
      schemaOpt: Option[IdSegment],
      f: DataResource => Json
  )(implicit caller: Caller) =
    authorizeFor(ref, resourcePermissions.read).apply {
      fetchResource(
        rev => emit(resources.fetchAt(id, ref, schemaOpt, rev).leftWiden[ResourceRejection].map(f).rejectWhen(wrongJsonOrNotFound)),
        tag => emit(resources.fetchBy(id, ref, schemaOpt, tag).leftWiden[ResourceRejection].map(f).rejectWhen(wrongJsonOrNotFound)),
        emit(resources.fetch(id, ref, schemaOpt).leftWiden[ResourceRejection].map(f).rejectWhen(wrongJsonOrNotFound))
      )
    }

  private val wrongJsonOrNotFound: PartialFunction[ResourceRejection, Boolean] = {
    case _: ResourceNotFound | _: InvalidSchemaRejection | _: InvalidJsonLdFormat => true
  }

}

object ResourcesRoutes {

  /** @return the [[Route]] for resources
    */
  def apply(
      identities: Identities,
      acls: Acls,
      orgs: Organizations,
      projects: Projects,
      resources: Resources,
      sseEventLog: SseEventLog
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ResourcesRoutes(identities, acls, orgs, projects, resources, sseEventLog).routes

}
