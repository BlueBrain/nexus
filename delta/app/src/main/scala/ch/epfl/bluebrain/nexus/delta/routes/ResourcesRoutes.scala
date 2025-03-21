package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{OriginalSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{NexusSource, Resources}

/**
  * The resource routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   verify the acls for users
  * @param resources
  *   the resources module
  * @param index
  *   the indexing action on write operations
  */
final class ResourcesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    resources: Resources,
    index: IndexingAction.Execute[Resource]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  private val resourceSchema = schemas.resources

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue.empty)

  private val nonGenericResourceCandidate: PartialFunction[ResourceRejection, Boolean] = {
    case _: ResourceNotFound | _: InvalidSchemaRejection | _: ReservedResourceTypes => true
  }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("resources") {
        extractCaller { implicit caller =>
          projectRef.apply { project =>
            concat(
              // Create a resource without schema nor id segment
              (pathEndOrSingleSlash & post & noParameter("rev") & entity(as[NexusSource]) & indexingMode & tagParam) {
                (source, mode, tag) =>
                  authorizeFor(project, Write).apply {
                    emit(
                      Created,
                      resources
                        .create(project, resourceSchema, source.value, tag)
                        .flatTap(index(project, _, mode))
                        .map(_.void)
                        .attemptNarrow[ResourceRejection]
                        .rejectWhen(nonGenericResourceCandidate)
                    )
                  }
              },
              (idSegment & indexingMode) { (schema, mode) =>
                val schemaOpt = underscoreToOption(schema)
                concat(
                  // Create a resource with schema but without id segment
                  (pathEndOrSingleSlash & post & noParameter("rev") & entity(as[NexusSource]) & tagParam) {
                    (source, tag) =>
                      authorizeFor(project, Write).apply {
                        emit(
                          Created,
                          resources
                            .create(project, schema, source.value, tag)
                            .flatTap(index(project, _, mode))
                            .map(_.void)
                            .attemptNarrow[ResourceRejection]
                            .rejectWhen(nonGenericResourceCandidate)
                        )
                      }
                  },
                  idSegment { resource =>
                    concat(
                      pathEndOrSingleSlash {
                        concat(
                          // Create or update a resource
                          put {
                            authorizeFor(project, Write).apply {
                              (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[NexusSource]) & tagParam) {
                                case (None, source, tag)      =>
                                  // Create a resource with schema and id segments
                                  emit(
                                    Created,
                                    resources
                                      .create(resource, project, schema, source.value, tag)
                                      .flatTap(index(project, _, mode))
                                      .map(_.void)
                                      .attemptNarrow[ResourceRejection]
                                      .rejectWhen(nonGenericResourceCandidate)
                                  )
                                case (Some(rev), source, tag) =>
                                  // Update a resource
                                  emit(
                                    resources
                                      .update(resource, project, schemaOpt, rev, source.value, tag)
                                      .flatTap(index(project, _, mode))
                                      .map(_.void)
                                      .attemptNarrow[ResourceRejection]
                                      .rejectWhen(nonGenericResourceCandidate)
                                  )
                              }
                            }
                          },
                          // Deprecate a resource
                          (pathEndOrSingleSlash & delete & parameter("rev".as[Int])) { rev =>
                            authorizeFor(project, Write).apply {
                              emit(
                                resources
                                  .deprecate(resource, project, schemaOpt, rev)
                                  .flatTap(index(project, _, mode))
                                  .map(_.void)
                                  .attemptNarrow[ResourceRejection]
                                  .rejectOn[ResourceNotFound]
                              )
                            }
                          },
                          // Fetch a resource
                          (pathEndOrSingleSlash & get & idSegmentRef(resource) & varyAcceptHeaders) { resourceRef =>
                            emitOrFusionRedirect(
                              project,
                              resourceRef,
                              authorizeFor(project, Read).apply {
                                emit(
                                  resources
                                    .fetch(resourceRef, project, schemaOpt)
                                    .attemptNarrow[ResourceRejection]
                                    .rejectOn[ResourceNotFound]
                                )
                              }
                            )
                          }
                        )
                      },
                      // Undeprecate a resource
                      (pathPrefix("undeprecate") & put & parameter("rev".as[Int])) { rev =>
                        authorizeFor(project, Write).apply {
                          emit(
                            resources
                              .undeprecate(resource, project, schemaOpt, rev)
                              .flatTap(index(project, _, mode))
                              .map(_.void)
                              .attemptNarrow[ResourceRejection]
                              .rejectOn[ResourceNotFound]
                          )
                        }
                      },
                      (pathPrefix("update-schema") & put & pathEndOrSingleSlash) {
                        authorizeFor(project, Write).apply {
                          emit(
                            IO.fromOption(schemaOpt)(NoSchemaProvided)
                              .flatMap { schema =>
                                resources
                                  .updateAttachedSchema(resource, project, schema)
                                  .flatTap(index(project, _, mode))
                              }
                              .attemptNarrow[ResourceRejection]
                              .rejectOn[ResourceNotFound]
                          )
                        }
                      },
                      (pathPrefix("refresh") & put & pathEndOrSingleSlash) {
                        authorizeFor(project, Write).apply {
                          emit(
                            OK,
                            resources
                              .refresh(resource, project, schemaOpt)
                              .flatTap(index(project, _, mode))
                              .map(_.void)
                              .attemptNarrow[ResourceRejection]
                              .rejectOn[ResourceNotFound]
                          )
                        }
                      },
                      // Fetch a resource original source
                      (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(resource) & varyAcceptHeaders) {
                        resourceRef =>
                          authorizeFor(project, Read).apply {
                            annotateSource { annotate =>
                              emit(
                                resources
                                  .fetch(resourceRef, project, schemaOpt)
                                  .map { resource => OriginalSource(resource, resource.value.source, annotate) }
                                  .attemptNarrow[ResourceRejection]
                                  .rejectOn[ResourceNotFound]
                              )
                            }
                          }
                      },
                      // Get remote contexts
                      pathPrefix("remote-contexts") {
                        (get & idSegmentRef(resource) & pathEndOrSingleSlash & authorizeFor(project, Read)) {
                          resourceRef =>
                            emit(
                              resources
                                .fetchState(resourceRef, project, schemaOpt)
                                .map(_.remoteContexts)
                                .attemptNarrow[ResourceRejection]
                            )
                        }
                      },
                      // Tag a resource
                      pathPrefix("tags") {
                        concat(
                          // Fetch a resource tags
                          (get & idSegmentRef(resource) & pathEndOrSingleSlash & authorizeFor(project, Read)) {
                            resourceRef =>
                              emit(
                                resources
                                  .fetch(resourceRef, project, schemaOpt)
                                  .map(_.value.tags)
                                  .attemptNarrow[ResourceRejection]
                                  .rejectOn[ResourceNotFound]
                              )
                          },
                          // Tag a resource
                          (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                            authorizeFor(project, Write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(
                                  Created,
                                  resources
                                    .tag(resource, project, schemaOpt, tag, tagRev, rev)
                                    .flatTap(index(project, _, mode))
                                    .map(_.void)
                                    .attemptNarrow[ResourceRejection]
                                    .rejectOn[ResourceNotFound]
                                )
                              }
                            }
                          },
                          // Delete a tag
                          (tagLabel & delete & parameter("rev".as[Int]) & pathEndOrSingleSlash & authorizeFor(
                            project,
                            Write
                          )) { (tag, rev) =>
                            emit(
                              resources
                                .deleteTag(resource, project, schemaOpt, tag, rev)
                                .flatTap(index(project, _, mode))
                                .map(_.void)
                                .attemptNarrow[ResourceRejection]
                                .rejectOn[ResourceNotFound]
                            )
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
    }
}

object ResourcesRoutes {

  /**
    * @return
    *   the [[Route]] for resources
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      resources: Resources,
      index: IndexingAction.Execute[Resource]
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = new ResourcesRoutes(identities, aclCheck, resources, index).routes

}
