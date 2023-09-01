package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas.shacl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.ce.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.schemas.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.SchemaNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{Schema, SchemaRejection}
import io.circe.{Json, Printer}
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
  * @param indexAction
  *   the indexing action on write operations
  */
final class SchemasRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    schemas: Schemas,
    schemeDirectives: DeltaSchemeDirectives,
    indexAction: IndexingAction.Execute[Schema]
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives._

  implicit private def resourceFAJsonLdEncoder[A: JsonLdEncoder]: JsonLdEncoder[ResourceF[A]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.schemasMetadata))

  private def emitFetch(io: IO[SchemaResource]): Route =
    emit(io.attemptNarrow[SchemaRejection].rejectOn[SchemaNotFound])

  private def emitMetadata(statusCode: StatusCode, io: IO[SchemaResource]): Route =
    emit(statusCode, io.map(_.void).attemptNarrow[SchemaRejection])

  private def emitMetadata(io: IO[SchemaResource]): Route = emitMetadata(StatusCodes.OK, io)

  private def emitMetadataOrReject(io: IO[SchemaResource]): Route =
    emit(io.map(_.void).attemptNarrow[SchemaRejection].rejectOn[SchemaNotFound])

  private def emitSource(io: IO[SchemaResource]): Route = {
    implicit val source: Printer = sourcePrinter
    emit(io.map(_.value.source).attemptNarrow[SchemaRejection].rejectOn[SchemaNotFound])
  }

  private def emitTags(io: IO[SchemaResource]): Route =
    emit(io.map(_.value.tags).attemptNarrow[SchemaRejection].rejectOn[SchemaNotFound])

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("schemas", shacl)) {
      pathPrefix("schemas") {
        extractCaller { implicit caller =>
          (resolveProjectRef & indexingMode) { (ref, mode) =>
            def index(schema: SchemaResource): IO[Unit] = indexAction(schema.value.project, schema, mode)
            concat(
              // Create a schema without id segment
              (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json])) { source =>
                authorizeFor(ref, Write).apply {
                  emitMetadata(Created, schemas.create(ref, source).flatTap(index))
                }
              },
              idSegment { id =>
                concat(
                  pathEndOrSingleSlash {
                    concat(
                      // Create or update a schema
                      put {
                        authorizeFor(ref, Write).apply {
                          (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                            case (None, source)      =>
                              // Create a schema with id segment
                              emitMetadata(Created, schemas.create(id, ref, source).flatTap(index))
                            case (Some(rev), source) =>
                              // Update a schema
                              emitMetadata(schemas.update(id, ref, rev, source).flatTap(index))
                          }
                        }
                      },
                      // Deprecate a schema
                      (delete & parameter("rev".as[Int])) { rev =>
                        authorizeFor(ref, Write).apply {
                          emitMetadataOrReject(schemas.deprecate(id, ref, rev).flatTap(index))
                        }
                      },
                      // Fetch a schema
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(
                          ref,
                          id,
                          authorizeFor(ref, Read).apply {
                            emitFetch(schemas.fetch(id, ref))
                          }
                        )
                      }
                    )
                  },
                  (pathPrefix("refresh") & put & pathEndOrSingleSlash) {
                    authorizeFor(ref, Write).apply {
                      emitMetadata(schemas.refresh(id, ref).flatTap(index))
                    }
                  },
                  // Fetch a schema original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    authorizeFor(ref, Read).apply {
                      emitSource(schemas.fetch(id, ref))
                    }
                  },
                  pathPrefix("tags") {
                    concat(
                      // Fetch a schema tags
                      (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(ref, Read)) { id =>
                        emitTags(schemas.fetch(id, ref))
                      },
                      // Tag a schema
                      (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                        authorizeFor(ref, Write).apply {
                          entity(as[Tag]) { case Tag(tagRev, tag) =>
                            emitMetadata(
                              Created,
                              schemas.tag(id, ref, tag, tagRev, rev).flatTap(index)
                            )
                          }
                        }
                      },
                      // Delete a tag
                      (tagLabel & delete & parameter("rev".as[Int]) & pathEndOrSingleSlash & authorizeFor(
                        ref,
                        Write
                      )) { (tag, rev) =>
                        emitMetadataOrReject(schemas.deleteTag(id, ref, tag, rev).flatTap(index))
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
      index: IndexingAction.Execute[Schema]
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = new SchemasRoutes(identities, aclCheck, schemas, schemeDirectives, index).routes

}
