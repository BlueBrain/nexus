package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedFormFieldRejection, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes.PatchPermissions._
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, Permissions}
import ch.epfl.bluebrain.nexus.delta.syntax._
import io.circe.Decoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The permissions routes.
  *
  * @param permissions the permissions operations bundle
  */
final class PermissionsRoutes(identities: Identities, permissions: Permissions)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities)
    with DeltaDirectives
    with CirceUnmarshalling {
  private val prefixSegment = baseUri.prefix.fold("")(p => s"/$p")

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractSubject { implicit subject =>
        concat(
          (pathPrefix("permissions") & pathEndOrSingleSlash) {
            operationName(s"$prefixSegment/permissions") {
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
                      reject(
                        malformedFormField(keywords.tpe, s"Expected value 'Replace' or 'Subtract' when using 'PATCH'.")
                      )
                  }
                },
                delete { // Delete permissions
                  parameter("rev".as[Long]) { rev =>
                    completeIO(permissions.delete(rev).map(_.void))
                  }
                }
              )
            }
          },
          (pathPrefix("permissions" / "events") & pathEndOrSingleSlash) {
            operationName(s"$prefixSegment/permissions/events") {
              lastEventId { offset =>
                completeStream(permissions.events(offset))
              }
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
  def apply(identities: Identities, permissions: Permissions)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new PermissionsRoutes(identities, permissions).routes

  sealed private[routes] trait PatchPermissions extends Product with Serializable

  private[routes] object PatchPermissions {

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
