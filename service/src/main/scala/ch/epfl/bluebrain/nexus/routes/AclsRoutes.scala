package ch.epfl.bluebrain.nexus.routes

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route}
import ch.epfl.bluebrain.nexus.acls._
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.directives.AclDirectives._
import ch.epfl.bluebrain.nexus.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.marshallers.instances._
import ch.epfl.bluebrain.nexus.realms.Realms
import ch.epfl.bluebrain.nexus.routes.AclsRoutes.PatchAcl.{AppendAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.routes.AclsRoutes._
import ch.epfl.bluebrain.nexus.auth.Caller
import io.circe.{Decoder, DecodingFailure}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class AclsRoutes(acls: Acls[Task], realms: Realms[Task])(implicit hc: HttpConfig) {

  private val simultaneousRevAndAncestorsRejection =
    MalformedQueryParamRejection("rev", "rev and ancestors query parameters cannot be present simultaneously")

  private val simultaneousRevAndAnyRejection =
    MalformedQueryParamRejection("rev", "rev query parameter and path containing * cannot be present simultaneously")

  def routes: Route =
    pathPrefix("acls") {
      extractAclTarget { aclTarget =>
        operationName(s"/${hc.prefix}/acls" + aclTarget.toString.split("/").map(_ => "/{}").mkString("")) { // /v1/acls/{}/{}
          authenticateOAuth2Async("*", authenticator(realms)).withAnonymousUser(Caller.anonymous) { implicit caller =>
            concat(
              parameter("rev" ? 0L) { rev =>
                val status = if (rev == 0L) Created else OK
                concat(
                  (put & entity(as[AccessControlList])) { acl =>
                    complete(acls.replace(aclTarget, rev, acl).runWithStatus(status))
                  },
                  (patch & entity(as[PatchAcl])) {
                    case AppendAcl(acl) =>
                      complete(acls.append(aclTarget, rev, acl).runWithStatus(status))
                    case SubtractAcl(acl) =>
                      complete(acls.subtract(aclTarget, rev, acl).runToFuture)
                  },
                  delete {
                    complete(acls.delete(aclTarget, rev).runToFuture)
                  }
                )
              },
              (get & parameter("rev".as[Long].?) & parameter("ancestors" ? false) & parameter("self" ? true)) {
                case (Some(_), true, _) =>
                  reject(simultaneousRevAndAncestorsRejection)
                case (Some(_), _, _) if aclTarget.hasAny =>
                  reject(simultaneousRevAndAnyRejection)
                case (_, ancestors, self) if aclTarget.hasAny =>
                  complete(acls.list(aclTarget, ancestors, self).runToFuture)
                case (Some(rev), false, self) =>
                  complete(acls.fetch(aclTarget, rev, self).toSingleList(aclTarget).runToFuture)
                case (_, false, self) =>
                  complete(acls.fetch(aclTarget, self).toSingleList(aclTarget).runToFuture)
                case (_, true, self) =>
                  complete(acls.list(aclTarget, ancestors = true, self).runToFuture)
              }
            )
          }
        }
      }
    }
}

object AclsRoutes {

  implicit private[routes] class TaskResourceACLSyntax(private val value: Task[ResourceOpt]) extends AnyVal {
    def toSingleList(target: AclTarget): Task[AccessControlLists] = value.map {
      case None                                                        => AccessControlLists.empty
      case Some(resource) if resource.value == AccessControlList.empty => AccessControlLists.empty
      case Some(resource)                                              => AccessControlLists(target -> resource.value)
    }
  }

  sealed private[routes] trait PatchAcl

  private[routes] object PatchAcl {

    final case class SubtractAcl(acl: AccessControlList) extends PatchAcl
    final case class AppendAcl(acl: AccessControlList)   extends PatchAcl

    implicit val patchAclDecoder: Decoder[PatchAcl] =
      Decoder.instance { hc =>
        for {
          tpe <- hc.get[String]("@type")
          acl <- hc.value.as[AccessControlList]
          patch <- tpe match {
                    case "Append"   => Right(AppendAcl(acl))
                    case "Subtract" => Right(SubtractAcl(acl))
                    case _          => Left(DecodingFailure("@type field must have Append or Subtract value", hc.history))
                  }
        } yield patch
      }
  }
}
