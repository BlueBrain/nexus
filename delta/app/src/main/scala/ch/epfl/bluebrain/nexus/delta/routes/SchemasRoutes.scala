package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas.shacl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.routes.models.{JsonSource, TagFields}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, schemas => schemaPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label}
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

  private val defaultAlias = ApiMappings.default.aliases

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        concat(
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
              ((label | orgLabelFromUuidLookup(organizations)) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
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
              // SSE schemas for all events belonging to a project
              (projectFromRefOrUUD & pathPrefix("events") & pathEndOrSingleSlash) { ref =>
                get {
                  operationName(s"$prefixSegment/schemas/{org}/{project}/events") {
                    authorizeFor(AclAddress.Project(ref), events.read).apply {
                      lastEventId { offset =>
                        emit(schemas.events(ref, offset).leftWiden[SchemaRejection])
                      }
                    }
                  }
                }
              }
            )
          },
          // Consumes 'schemas/{org}/{project}' or 'resources/{org}/{project}/{schema}'
          ((pathPrefix("schemas") & projectFromRefOrUUD & provide(IriSegment(shacl))) |
            (pathPrefix("resources") & projectFromRefOrUUD & idSegment)) { (ref, schemaSegment) =>
            isShacl(ref, schemaSegment) { isShacl =>
              concat(
                // Create a schema without id segment
                (post & noParameter("rev") & pathEndOrSingleSlash & passIf(isShacl) & entity(as[Json])) { source =>
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
                          put {
                            authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
                              (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                case (None, _) if !isShacl => reject()
                                case (None, source)        =>
                                  // Create a schema with id segment
                                  emit(Created, schemas.create(id, ref, source).map(_.void))
                                case (Some(rev), source)   =>
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
                          // Fetches a schema
                          get {
                            authorizeFor(AclAddress.Project(ref), schemaPermissions.read).apply {
                              (parameter("rev".as[Long].?) & parameter("tag".as[Label].?)) {
                                case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
                                case (Some(rev), _)     => emit(schemas.fetchAt(id, ref, rev))
                                case (_, Some(tag))     => emit(schemas.fetchBy(id, ref, tag))
                                case _                  => emit(schemas.fetch(id, ref))
                              }
                            }
                          }
                        )
                      }
                    },
                    // Fetches a schema original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/source") {
                        authorizeFor(AclAddress.Project(ref), schemaPermissions.read).apply {
                          (parameter("rev".as[Long].?) & parameter("tag".as[Label].?)) {
                            case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
                            case (Some(rev), _)     => emit(schemas.fetchAt(id, ref, rev).map(asSource))
                            case (_, Some(tag))     => emit(schemas.fetchBy(id, ref, tag).map(asSource))
                            case _                  => emit(schemas.fetch(id, ref).map(asSource))
                          }
                        }
                      }
                    },
                    // Tag a schema
                    (pathPrefix("tags") & post & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
                      authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
                        entity(as[TagFields]) { case TagFields(tagRev, tag) =>
                          operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/tags") {
                            emit(schemas.tag(id, ref, tag, tagRev, rev).map(_.void))
                          }
                        }
                      }
                    }
                  )
                }
              )
            }
          }
        )
      }
    }

  private def projectFromRefOrUUD =
    projectRef | projectRefFromUuidsLookup(projects)

  private def passIf(value: Boolean): Directive0 =
    if (value) pass else reject()

  private def isShacl(ref: ProjectRef, idSegment: IdSegment): Directive1[Boolean] =
    idSegment match {
      case IriSegment(`shacl`)                                    => provide(true)
      case StringSegment("_")                                     => provide(false)
      case IriSegment(_)                                          => reject()
      case StringSegment(str) if defaultAlias.exists { case (k, iri) => k == str && iri == shacl } => provide(true)
      case StringSegment(string) if defaultAlias.contains(string) => reject()
      case segment: StringSegment                                 =>
        onSuccess(projects.fetch(ref).runToFuture)
          .map(_.flatMap(res => segment.toIri(res.value.apiMappings, res.value.base)))
          .flatMap {
            case Some(`shacl`) => provide(true)
            case _             => reject()
          }
    }

  private def asSource(resourceOpt: Option[SchemaResource]): Option[JsonSource] =
    resourceOpt.map(resource => JsonSource(resource.value.source, resource.value.id))

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
