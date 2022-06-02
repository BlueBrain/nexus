package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes.PatchPermissions._
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, permissions => permissionsPerms}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import io.circe.{Decoder, Json}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.syntax._
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

import scala.annotation.nowarn

/**
  * The permissions routes.
  *
  * @param identities
  *   the identities operations bundle
  * @param permissions
  *   the permissions operations bundle
  */
final class PermissionsRoutes(identities: Identities, permissions: Permissions, acls: Acls)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  implicit private val fetchProjectUuids: FetchUuids                              = _ => UIO.none
  implicit private val resourceFUnitJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.permissionsMetadata))

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("permissions") {
        extractCaller { implicit caller =>
          concat(
            pathEndOrSingleSlash {
              operationName(s"$prefixSegment/permissions") {
                concat(
                  // Fetch permissions
                  get {
                    authorizeFor(AclAddress.Root, permissionsPerms.read).apply {
                      parameter("rev".as[Long].?) {
                        case Some(rev) => emit(permissions.fetchAt(rev).leftWiden[PermissionsRejection])
                        case None      => emit(permissions.fetch)
                      }
                    }
                  },
                  // Replace permissions
                  (put & parameter("rev" ? 0L)) { rev =>
                    authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                      entity(as[PatchPermissions]) {
                        case Replace(set) => emit(permissions.replace(set, rev).map(_.void))
                        case _            =>
                          reject(
                            malformedContent(s"Value for field '${keywords.tpe}' must be 'Replace' when using 'PUT'.")
                          )
                      }
                    }
                  },
                  // Append or Subtract permissions
                  (patch & parameter("rev" ? 0L)) { rev =>
                    authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                      entity(as[PatchPermissions]) {
                        case Append(set)   => emit(permissions.append(set, rev).map(_.void))
                        case Subtract(set) => emit(permissions.subtract(set, rev).map(_.void))
                        case _             =>
                          reject(
                            malformedContent(
                              s"Value for field '${keywords.tpe}' must be 'Append' or 'Subtract' when using 'PATCH'."
                            )
                          )
                      }
                    }
                  },
                  // Delete permissions
                  delete {
                    authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                      parameter("rev".as[Long]) { rev =>
                        emit(permissions.delete(rev).map(_.void))
                      }
                    }
                  }
                )
              }
            },
            // SSE permissions
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/permissions/events") {
                authorizeFor(AclAddress.Root, events.read).apply {
                  lastEventId { offset =>
                    emit(permissions.events(offset))
                  }
                }
              }
            }
          )
        }
      }
    }

  private def malformedContent(field: String) =
    MalformedRequestContentRejection(field, new IllegalArgumentException())
}

object PermissionsRoutes {

  /**
    * @return
    *   the [[Route]] for the permission resources
    */
  def apply(identities: Identities, permissions: Permissions, acls: Acls)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new PermissionsRoutes(identities, permissions, acls).routes

  sealed private[routes] trait PatchPermissions extends Product with Serializable

  private[routes] object PatchPermissions {

    final case class Append(permissions: Set[Permission])   extends PatchPermissions
    final case class Subtract(permissions: Set[Permission]) extends PatchPermissions
    final case class Replace(permissions: Set[Permission])  extends PatchPermissions

    @nowarn("cat=unused")
    implicit final private val configuration: Configuration =
      Configuration.default.withStrictDecoding.withDiscriminator(keywords.tpe)

    private val replacedType = Json.obj(keywords.tpe -> "Replace".asJson)

    implicit val patchPermissionsDecoder: Decoder[PatchPermissions] =
      Decoder.instance { hc =>
        deriveConfiguredDecoder[PatchPermissions].decodeJson(replacedType deepMerge hc.value)
      }
  }

}
