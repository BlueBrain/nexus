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
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
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
              (projectRef | projectRefFromUuidsLookup(projects)) { ref =>
                projectRoutes(ref, allowCreateRoute = true)
              }
            )
          },
          // Attempt using the alternative API endpoints /resources/{org}/{proj}/{schema}
          // {schema} can be anything resolving to shacl schema Iri or _
          pathPrefix("resources") {
            (projectRef | projectRefFromUuidsLookup(projects)) { ref =>
              idSegment { schemaSegment =>
                isShacl(ref, schemaSegment) { isSchema =>
                  projectRoutes(ref, allowCreateRoute = isSchema)
                }
              }
            }
          }
        )
      }
    }

  private def projectRoutes(ref: ProjectRef, allowCreateRoute: Boolean)(implicit caller: Caller): Route =
    concat(
      // SSE schemas for all events belonging to a project
      (pathPrefix("events") & pathEndOrSingleSlash) {
        get {
          operationName(s"$prefixSegment/schemas/{org}/{project}/events") {
            authorizeFor(AclAddress.Project(ref), events.read).apply {
              lastEventId { offset =>
                emit(schemas.events(ref, offset).leftWiden[SchemaRejection])
              }
            }
          }
        }
      },
      // Create a schema without id segment
      (post & noParameter("rev") & pathEndOrSingleSlash & entity(as[Json])) { source =>
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
                      case (None, _) if !allowCreateRoute => reject()
                      case (None, source)                 =>
                        // Create a schema with id segment
                        emit(Created, schemas.create(id, ref, source).map(_.void))
                      case (Some(rev), source)            =>
                        // Update a schema
                        emit(schemas.update(id, ref, rev, source).map(_.void))
                    }
                  }
                },
                (delete & parameter("rev".as[Long])) { rev =>
                  authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
                    // Deprecate a schema
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
          (pathPrefix("tags") & post & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
            authorizeFor(AclAddress.Project(ref), schemaPermissions.write).apply {
              entity(as[TagFields]) { case TagFields(tagRev, tag) =>
                operationName(s"$prefixSegment/schemas/{org}/{project}/{id}/tags") {
                  // Tag a schema
                  emit(schemas.tag(id, ref, tag, tagRev, rev).map(_.void))
                }
              }
            }
          }
        )
      }
    )

  private def isShacl(ref: ProjectRef, idSegment: IdSegment): Directive1[Boolean] =
    idSegment match {
      case IriSegment(`shacl`)       => provide(true)
      case IriSegment(_)             => reject()
      case StringSegment("_")        => provide(false)
      case StringSegment(string) if defaultAlias.exists { case (k, iri) => k == string && iri == shacl } =>
        provide(true)
      case strSegment: StringSegment =>
        onSuccess(projects.fetch(ref).runToFuture).flatMap {
          case Some(resource) =>
            strSegment.toIri(resource.value.apiMappings, resource.value.base) match {
              case Some(`shacl`) => provide(true)
              case _             => reject()
            }
          case _              => reject()
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
