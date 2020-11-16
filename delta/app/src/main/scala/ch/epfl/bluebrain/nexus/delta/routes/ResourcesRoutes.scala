package ch.epfl.bluebrain.nexus.delta.routes

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
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
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The resource routes
  *
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  * @param resources  the resources module
  */
final class ResourcesRoutes(
    identities: Identities,
    acls: Acls,
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
            // SSE resources
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
            projectRef { ref =>
              routesForProject(ref)
            },
            (uuid & uuid) { (orgUuid, projectUuid) =>
              fetchProject(orgUuid, projectUuid) { project =>
                routesForProject(project.value.ref)
              }
            }
          )
        }
      }
    }

  private def routesForProject(ref: ProjectRef)(implicit caller: Caller): Route =
    concat(
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
            emit(StatusCodes.Created, resources.create(ref, resourceSchema, source).map(_.void))
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
                  emit(StatusCodes.Created, resources.create(ref, schemaSegment, source).map(_.void))
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
                          emit(StatusCodes.Created, resources.create(idSegment, ref, schemaSegment, source).map(_.void))
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

  private def fetchProject(orgUuid: UUID, projectUuid: UUID): Directive1[ProjectResource] =
    onSuccess(projects.fetch(projectUuid).runToFuture).flatMap {
      case Some(project) if project.value.organizationUuid == orgUuid => provide(project)
      case Some(_)                                                    => Directive(_ => discardEntityAndEmit(ProjectNotFound(orgUuid, projectUuid): ProjectRejection))
      case None                                                       => failWith(AuthorizationFailed)
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
  def apply(identities: Identities, acls: Acls, projects: Projects, resources: Resources)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ResourcesRoutes(identities, acls, projects, resources).routes

}
