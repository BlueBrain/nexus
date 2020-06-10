package ch.epfl.bluebrain.nexus.iam.routes

import akka.http.javadsl.server.Rejections._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.iam.directives.AuthDirectives.authenticator
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.routes.PermissionsRoutes.PatchPermissions
import ch.epfl.bluebrain.nexus.iam.routes.PermissionsRoutes.PatchPermissions.{Append, Replace, Subtract}
import ch.epfl.bluebrain.nexus.iam.types.ResourceF._
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
import io.circe.{Decoder, DecodingFailure}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * The permissions routes.
  *
  * @param permissions the permissions api
  * @param realms      the realms api
  */
class PermissionsRoutes(permissions: Permissions[Task], realms: Realms[Task])(implicit http: HttpConfig) {

  def routes: Route =
    (pathPrefix("permissions") & pathEndOrSingleSlash) {
      operationName(s"/${http.prefix}/permissions") {
        authenticateOAuth2Async("*", authenticator(realms)).withAnonymousUser(Caller.anonymous) { implicit caller =>
          concat(
            get {
              parameter("rev".as[Long].?) {
                case Some(rev) => complete(permissions.fetchAt(rev).runNotFound)
                case None      => complete(permissions.fetch.runToFuture)
              }
            },
            (put & parameter("rev" ? 0L)) { rev =>
              entity(as[PatchPermissions]) {
                case Replace(set) =>
                  complete(permissions.replace(set, rev).runToFuture)
                case _ => reject(validationRejection("Only @type 'Replace' is permitted when using 'put'."))
              }
            },
            delete {
              parameter("rev".as[Long]) { rev => complete(permissions.delete(rev).runToFuture) }
            },
            (patch & parameter("rev" ? 0L)) { rev =>
              entity(as[PatchPermissions]) {
                case Append(set) =>
                  complete(permissions.append(set, rev).runToFuture)
                case Subtract(set) =>
                  complete(permissions.subtract(set, rev).runToFuture)
                case _ =>
                  reject(validationRejection("Only @type 'Append' or 'Subtract' is permitted when using 'patch'."))
              }
            }
          )
        }
      }
    }
}

object PermissionsRoutes {

  sealed private[routes] trait PatchPermissions extends Product with Serializable

  private[routes] object PatchPermissions {

    final case class Append(permissions: Set[Permission])   extends PatchPermissions
    final case class Subtract(permissions: Set[Permission]) extends PatchPermissions
    final case class Replace(permissions: Set[Permission])  extends PatchPermissions

    implicit val patchPermissionsDecoder: Decoder[PatchPermissions] =
      Decoder.instance { hc =>
        for {
          permissions <- hc.get[Set[Permission]]("permissions")
          tpe         = hc.get[String]("@type").getOrElse("Replace")
          patch <- tpe match {
                    case "Replace"  => Right(Replace(permissions))
                    case "Append"   => Right(Append(permissions))
                    case "Subtract" => Right(Subtract(permissions))
                    case _          => Left(DecodingFailure("@type field must have Append or Subtract value", hc.history))
                  }
        } yield patch
      }
  }

}
