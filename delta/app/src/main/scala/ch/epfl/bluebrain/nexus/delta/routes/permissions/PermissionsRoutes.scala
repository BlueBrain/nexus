package ch.epfl.bluebrain.nexus.delta.routes.permissions

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedFormFieldRejection, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.permissions.PermissionsRoutes.PatchPermissions
import ch.epfl.bluebrain.nexus.delta.routes.permissions.PermissionsRoutes.PatchPermissions._
import ch.epfl.bluebrain.nexus.delta.routes.{CirceUnmarshalling, DeltaRouteDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsRejection}
import ch.epfl.bluebrain.nexus.delta.syntax._
import io.circe.Decoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The permissions routes.
  *
  * @param permissions the permissions operations bundle
  */
final class PermissionsRoutes(permissions: Permissions)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends DeltaRouteDirectives
    with CirceUnmarshalling {
  private val prefixSegment = baseUri.prefix.fold("")(p => s"/$p")

  //TODO: Remove this when having proper Identity extraction mechanism
  implicit private val caller: Subject = Identity.Anonymous

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & pathPrefix("permissions") & pathEndOrSingleSlash) {
      operationName(s"$prefixSegment/permissions") {
//        extractCaller { implicit caller =>
        concat(
          get { // Fetch permissions
            parameter("rev".as[Long].?) {
              case Some(rev) => completeIO(permissions.fetchAt(rev).leftMap[PermissionsRejection](identity))
              case None      => completeUIO(permissions.fetch)
            }
          },
          (put & parameter("rev" ? 0L)) { rev => // Replace permissions
            entity(as[PatchPermissions]) {
              case Replace(set) => completeIO(permissions.replace(set, rev).map(_.void))
              case _            => reject(malformedFormField(keywords.tpe, s"Expected value 'Replace' when using 'PUT'."))
            }
          },
          (patch & parameter("rev" ? 0L)) { rev => // Append or Subtract permissions
            entity(as[PatchPermissions]) {
              case Append(set)   => completeIO(permissions.append(set, rev).map(_.void))
              case Subtract(set) => completeIO(permissions.subtract(set, rev).map(_.void))
              case _             =>
                reject(malformedFormField(keywords.tpe, s"Expected value 'Replace' or 'Subtract' when using 'PATCH'."))
            }
          },
          delete { // Delete permissions
            parameter("rev".as[Long]) { rev =>
              completeIO(permissions.delete(rev).map(_.void))
            }
          }
        )
      }
    }

  private def malformedFormField(field: String, details: String) =
    MalformedFormFieldRejection(field, details, None)
}

object PermissionsRoutes {

  /**
    * @return the [[Route]] for the permission resources
    */
  def apply(permissions: Permissions)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new PermissionsRoutes(permissions).routes

  sealed private[permissions] trait PatchPermissions extends Product with Serializable

  private[permissions] object PatchPermissions {

    final case class Append(permissions: Set[Permission])   extends PatchPermissions
    final case class Subtract(permissions: Set[Permission]) extends PatchPermissions
    final case class Replace(permissions: Set[Permission])  extends PatchPermissions
    final case object Unexpected                            extends PatchPermissions

    implicit val patchPermissionsDecoder: Decoder[PatchPermissions] =
      Decoder.instance { hc =>
        (hc.get[Set[Permission]]("permissions"), hc.getIgnoreSingleArrayOr(keywords.tpe)("Replace")).mapN {
          case (permissions, "Replace")  => Replace(permissions)
          case (permissions, "Append")   => Append(permissions)
          case (permissions, "Subtract") => Subtract(permissions)
          case _                         => Unexpected
        }
      }
  }

}
