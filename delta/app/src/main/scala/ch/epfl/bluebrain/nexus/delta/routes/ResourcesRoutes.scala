package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, resources => resourcePermissions}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label}
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The resource routes
  *
  * @param identities    the identity module
  * @param acls          the ACLs module
  * @param organizations the organizations module
  * @param projects      the projects module
  * @param resources     the resources module
  */
final class ResourcesRoutes(
    identities: Identities,
    acls: Acls,
    organizations: Organizations,
    projects: Projects,
    resources: Resources
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  private val resourceSchema = IriSegment(schemas.resources)

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
                      emit(resources.events(offset))
                    }
                  }
                }
              }
            },
            // SSE resources for all events belonging to an organization
            ((label | orgLabelFromUuidLookup) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
              get {
                operationName(s"$prefixSegment/resources/{org}/events") {
                  authorizeFor(AclAddress.Organization(org), events.read).apply {
                    lastEventId { offset =>
                      emit(resources.events(org, offset).leftWiden[ResourceRejection])
                    }
                  }
                }
              }
            },
            (projectRef | projectRefFromUuidsLookup(projects)) { ref =>
              concat(
                // SSE resources for all events belonging to a project
                (pathPrefix("events") & pathEndOrSingleSlash) {
                  get {
                    operationName(s"$prefixSegment/resources/{org}/{project}/events") {
                      authorizeFor(AclAddress.Project(ref), events.read).apply {
                        lastEventId { offset =>
                          emit(resources.events(ref, offset).leftWiden[ResourceRejection])
                        }
                      }
                    }
                  }
                },
                // Create a resource without schema nor id segment
                (post & noParameter("rev") & pathEndOrSingleSlash & entity(as[Json])) { source =>
                  operationName(s"$prefixSegment/resources/{org}/{project}") {
                    authorizeFor(AclAddress.Project(ref), resourcePermissions.write).apply {
                      emit(Created, resources.create(ref, resourceSchema, source).map(_.void))
                    }
                  }
                },
                idSegment { schemaSegment =>
                  val schemaSegmentOpt = underscoreToOption(schemaSegment)
                  concat(
                    // Create a resource with schema but without id segment
                    (post & noParameter("rev") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/resources/{org}/{project}/{schema}") {
                        authorizeFor(AclAddress.Project(ref), resourcePermissions.write).apply {
                          entity(as[Json]) { source =>
                            emit(Created, resources.create(ref, schemaSegment, source).map(_.void))
                          }
                        }
                      }
                    },
                    idSegment { idSegment =>
                      concat(
                        operationName(s"$prefixSegment/resources/{org}/{project}/{schema}/{id}") {
                          concat(
                            (put & pathEndOrSingleSlash) {
                              authorizeFor(AclAddress.Project(ref), resourcePermissions.write).apply {
                                (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                  case (None, source)      =>
                                    // Create a resource with schema and id segments
                                    emit(Created, resources.create(idSegment, ref, schemaSegment, source).map(_.void))
                                  case (Some(rev), source) =>
                                    // Update a resource
                                    emit(resources.update(idSegment, ref, schemaSegmentOpt, rev, source).map(_.void))
                                }
                              }
                            },
                            (delete & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
                              authorizeFor(AclAddress.Project(ref), resourcePermissions.write).apply {
                                // Deprecate a resource
                                emit(resources.deprecate(idSegment, ref, schemaSegmentOpt, rev).map(_.void))
                              }
                            },
                            // Fetches a resource
                            (get & pathEndOrSingleSlash) {
                              authorizeFor(AclAddress.Project(ref), resourcePermissions.read).apply {
                                (parameter("rev".as[Long].?) & parameter("tag".as[Label].?)) {
                                  case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
                                  case (Some(rev), _)     => emit(resources.fetchAt(idSegment, ref, schemaSegmentOpt, rev))
                                  case (_, Some(tag))     => emit(resources.fetchBy(idSegment, ref, schemaSegmentOpt, tag))
                                  case _                  => emit(resources.fetch(idSegment, ref, schemaSegmentOpt))
                                }
                              }
                            }
                          )
                        },
                        (pathPrefix("tags") & post & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
                          authorizeFor(AclAddress.Project(ref), resourcePermissions.write).apply {
                            entity(as[TagFields]) { case TagFields(tagRev, tag) =>
                              operationName(s"$prefixSegment/resources/{org}/{project}/{schema}/{id}/tags") {
                                // Tag a resource
                                emit(resources.tag(idSegment, ref, schemaSegmentOpt, tag, tagRev, rev).map(_.void))
                              }
                            }
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

  private def orgLabelFromUuidLookup: Directive1[Label] =
    uuid.flatMap { orgUuid =>
      onSuccess(organizations.fetch(orgUuid).runToFuture).flatMap {
        case Some(resource) => provide(resource.value.label)
        case None           => failWith(AuthorizationFailed)
      }
    }

  private def underscoreToOption(segment: IdSegment): Option[IdSegment] =
    segment match {
      case StringSegment("_") => None
      case other              => Some(other)
    }

  private val simultaneousTagAndRevRejection =
    MalformedQueryParamRejection("tag", "tag and rev query parameters cannot be present simultaneously")

}

object ResourcesRoutes {

  /**
    * @return the [[Route]] for resources
    */
  def apply(identities: Identities, acls: Acls, orgs: Organizations, projects: Projects, resources: Resources)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ResourcesRoutes(identities, acls, orgs, projects, resources).routes

}
