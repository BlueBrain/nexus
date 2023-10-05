package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes.asSourceWithMetadata
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{AnnotatedSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.NexusSource.DecodingOption
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidJsonLdFormat, InvalidSchemaRejection, ResourceNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{NexusSource, Resources}
import io.circe.{Json, Printer}
import monix.bio.IO
import monix.execution.Scheduler

/**
  * The resource routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   verify the acls for users
  * @param resources
  *   the resources module
  * @param schemeDirectives
  *   directives related to orgs and projects
  * @param index
  *   the indexing action on write operations
  */
final class ResourcesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    resources: Resources,
    schemeDirectives: DeltaSchemeDirectives,
    index: IndexingAction.Execute[Resource]
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig,
    decodingOption: DecodingOption
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives._

  private val resourceSchema = schemas.resources

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue.empty)

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("resources") {
        extractCaller { implicit caller =>
          resolveProjectRef.apply { ref =>
            concat(
              // Create a resource without schema nor id segment
              (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[NexusSource]) & indexingMode) {
                (source, mode) =>
                  authorizeFor(ref, Write).apply {
                    emit(
                      Created,
                      resources.create(ref, resourceSchema, source.value).tapEval(index(ref, _, mode)).map(_.void)
                    )
                  }
              },
              (idSegment & indexingMode) { (schema, mode) =>
                val schemaOpt = underscoreToOption(schema)
                concat(
                  // Create a resource with schema but without id segment
                  (post & pathEndOrSingleSlash & noParameter("rev")) {
                    authorizeFor(ref, Write).apply {
                      entity(as[NexusSource]) { source =>
                        emit(
                          Created,
                          resources
                            .create(ref, schema, source.value)
                            .tapEval(index(ref, _, mode))
                            .map(_.void)
                            .rejectWhen(wrongJsonOrNotFound)
                        )
                      }
                    }
                  },
                  idSegment { id =>
                    concat(
                      pathEndOrSingleSlash {
                        concat(
                          // Create or update a resource
                          put {
                            authorizeFor(ref, Write).apply {
                              (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[NexusSource])) {
                                case (None, source)      =>
                                  // Create a resource with schema and id segments
                                  emit(
                                    Created,
                                    resources
                                      .create(id, ref, schema, source.value)
                                      .tapEval(index(ref, _, mode))
                                      .map(_.void)
                                      .rejectWhen(wrongJsonOrNotFound)
                                  )
                                case (Some(rev), source) =>
                                  // Update a resource
                                  emit(
                                    resources
                                      .update(id, ref, schemaOpt, rev, source.value)
                                      .tapEval(index(ref, _, mode))
                                      .map(_.void)
                                      .rejectWhen(wrongJsonOrNotFound)
                                  )
                              }
                            }
                          },
                          // Deprecate a resource
                          (delete & parameter("rev".as[Int])) { rev =>
                            authorizeFor(ref, Write).apply {
                              emit(
                                resources
                                  .deprecate(id, ref, schemaOpt, rev)
                                  .tapEval(index(ref, _, mode))
                                  .map(_.void)
                                  .rejectWhen(wrongJsonOrNotFound)
                              )
                            }
                          },
                          // Fetch a resource
                          (get & idSegmentRef(id) & varyAcceptHeaders) { id =>
                            emitOrFusionRedirect(
                              ref,
                              id,
                              authorizeFor(ref, Read).apply {
                                emit(
                                  resources
                                    .fetch(id, ref, schemaOpt)
                                    .leftWiden[ResourceRejection]
                                    .rejectWhen(wrongJsonOrNotFound)
                                )
                              }
                            )
                          }
                        )
                      },
                      (pathPrefix("refresh") & put & pathEndOrSingleSlash) {
                        authorizeFor(ref, Write).apply {
                          emit(
                            OK,
                            resources
                              .refresh(id, ref, schemaOpt)
                              .tapEval(index(ref, _, mode))
                              .map(_.void)
                              .rejectWhen(wrongJsonOrNotFound)
                          )
                        }
                      },
                      // Fetch a resource original source
                      (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id) & varyAcceptHeaders) { id =>
                        authorizeFor(ref, Read).apply {
                          (parameter("annotate".as[Boolean].withDefault(false))) { annotate =>
                            implicit val source: Printer = sourcePrinter
                            if (annotate) {
                              emit(
                                resources
                                  .fetch(id, ref, schemaOpt)
                                  .flatMap(asSourceWithMetadata)
                                  .rejectWhen(wrongJsonOrNotFound)
                              )
                            } else {
                              val sourceIO = resources.fetch(id, ref, schemaOpt).map(_.value.source)
                              val value    = sourceIO.leftWiden[ResourceRejection]
                              emit(value.rejectWhen(wrongJsonOrNotFound))
                            }
                          }
                        }
                      },
                      // Get remote contexts
                      pathPrefix("remote-contexts") {
                        (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(ref, Read)) { id =>
                          val remoteContextsIO = resources.fetchState(id, ref, schemaOpt).map(_.remoteContexts)
                          emit(remoteContextsIO.leftWiden[ResourceRejection])
                        }
                      },
                      // Tag a resource
                      pathPrefix("tags") {
                        concat(
                          // Fetch a resource tags
                          (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(ref, Read)) { id =>
                            val tagsIO = resources.fetch(id, ref, schemaOpt).map(_.value.tags)
                            emit(tagsIO.leftWiden[ResourceRejection].rejectWhen(wrongJsonOrNotFound))
                          },
                          // Tag a resource
                          (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                            authorizeFor(ref, Write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(
                                  Created,
                                  resources
                                    .tag(id, ref, schemaOpt, tag, tagRev, rev)
                                    .tapEval(index(ref, _, mode))
                                    .map(_.void)
                                    .rejectWhen(wrongJsonOrNotFound)
                                )
                              }
                            }
                          },
                          // Delete a tag
                          (tagLabel & delete & parameter("rev".as[Int]) & pathEndOrSingleSlash & authorizeFor(
                            ref,
                            Write
                          )) { (tag, rev) =>
                            emit(
                              resources
                                .deleteTag(id, ref, schemaOpt, tag, rev)
                                .tapEval(index(ref, _, mode))
                                .map(_.void)
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

  private val wrongJsonOrNotFound: PartialFunction[ResourceRejection, Boolean] = {
    case _: ResourceNotFound | _: InvalidSchemaRejection | _: InvalidJsonLdFormat => true
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
      projectsDirectives: DeltaSchemeDirectives,
      index: IndexingAction.Execute[Resource]
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig,
      decodingOption: DecodingOption
  ): Route = new ResourcesRoutes(identities, aclCheck, resources, projectsDirectives, index).routes

  def asSourceWithMetadata(
      resource: ResourceF[Resource]
  )(implicit baseUri: BaseUri, cr: RemoteContextResolution): IO[ResourceRejection, Json] =
    AnnotatedSource(resource, resource.value.source).mapError(e => InvalidJsonLdFormat(Some(resource.id), e))

}
