package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes.PatchPermissions._
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, permissions => permissionsPerms}
import io.circe.{Decoder, Json}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.syntax._
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

import scala.annotation.nowarn

/**
  * The permissions routes.
  *
  * @param identities  the identities operations bundle
  * @param permissions the permissions operations bundle
  */
final class PermissionsRoutes(identities: Identities, permissions: Permissions, acls: Acls)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        concat(
          (pathPrefix("permissions") & pathEndOrSingleSlash) {
            operationName(s"$prefixSegment/permissions") {
              concat(
                get { // Fetch permissions
                  authorizeFor(AclAddress.Root, permissionsPerms.read).apply {
                    parameter("rev".as[Long].?) {
                      case Some(rev) => completeIO(permissions.fetchAt(rev).leftWiden[PermissionsRejection])
                      case None      => completeUIO(permissions.fetch)
                    }
                  }
                },
                (put & parameter("rev" ? 0L)) { rev => // Replace permissions
                  authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                    entity(as[PatchPermissions]) {
                      case Replace(set) => completeIO(permissions.replace(set, rev).map(_.void))
                      case _            =>
                        reject(
                          malformedContent(s"Value for field '${keywords.tpe}' must be 'Replace' when using 'PUT'.")
                        )
                    }
                  }
                },
                (patch & parameter("rev" ? 0L)) { rev => // Append or Subtract permissions
                  authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                    entity(as[PatchPermissions]) {
                      case Append(set)   => completeIO(permissions.append(set, rev).map(_.void))
                      case Subtract(set) => completeIO(permissions.subtract(set, rev).map(_.void))
                      case _             =>
                        reject(
                          malformedContent(
                            s"Value for field '${keywords.tpe}' must be 'Append' or 'Subtract' when using 'PATCH'."
                          )
                        )
                    }
                  }
                },
                delete { // Delete permissions
                  authorizeFor(AclAddress.Root, permissionsPerms.write).apply {
                    parameter("rev".as[Long]) { rev =>
                      completeIO(permissions.delete(rev).map(_.void))
                    }
                  }
                }
              )
            }
          },
          (pathPrefix("permissions" / "events") & pathEndOrSingleSlash) {
            operationName(s"$prefixSegment/permissions/events") {
              authorizeFor(AclAddress.Root, events.read).apply {
                lastEventId { offset =>
                  completeStream(permissions.events(offset))
                }
              }
            }
          }
        )
      }
    }

  private def malformedContent(field: String) =
    MalformedRequestContentRejection(field, new IllegalArgumentException())
}

object PermissionsRoutes {

  /**
    * @return the [[Route]] for the permission resources
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
