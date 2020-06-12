package ch.epfl.bluebrain.nexus.iam.routes

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route}
import ch.epfl.bluebrain.nexus.iam.acls._
import ch.epfl.bluebrain.nexus.iam.directives.AclDirectives._
import ch.epfl.bluebrain.nexus.iam.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.routes.AclsRoutes.PatchAcl.{AppendAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.iam.routes.AclsRoutes._
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
import io.circe.{Decoder, DecodingFailure}
import kamon.instrumentation.akka.http.TracingDirectives._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class AclsRoutes(acls: Acls[Task], realms: Realms[Task])(implicit hc: HttpConfig) {

  private val any = "*"

  private val simultaneousRevAndAncestorsRejection =
    MalformedQueryParamRejection("rev", "rev and ancestors query parameters cannot be present simultaneously")

  private val simultaneousRevAndAnyRejection =
    MalformedQueryParamRejection("rev", "rev query parameter and path containing * cannot be present simultaneously")

  def routes: Route =
    pathPrefix("acls") {
      extractResourcePath { path =>
        operationName(s"/${hc.prefix}/acls" + path.segments.map(_ => "/{}").mkString("")) { // /v1/acls/{}/{}
          authenticateOAuth2Async("*", authenticator(realms)).withAnonymousUser(Caller.anonymous) { implicit caller =>
            concat(
              parameter("rev" ? 0L) { rev =>
                val status = if (rev == 0L) Created else OK
                concat(
                  (put & entity(as[AccessControlList])) { acl =>
                    complete(acls.replace(path, rev, acl).runWithStatus(status))
                  },
                  (patch & entity(as[PatchAcl])) {
                    case AppendAcl(acl)   =>
                      complete(acls.append(path, rev, acl).runWithStatus(status))
                    case SubtractAcl(acl) =>
                      complete(acls.subtract(path, rev, acl).runToFuture)
                  },
                  delete {
                    complete(acls.delete(path, rev).runToFuture)
                  }
                )
              },
              (get & parameter("rev".as[Long].?) & parameter("ancestors" ? false) & parameter("self" ? true)) {
                case (Some(_), true, _)                                  =>
                  reject(simultaneousRevAndAncestorsRejection)
                case (Some(_), _, _) if path.segments.contains(any)      =>
                  reject(simultaneousRevAndAnyRejection)
                case (_, ancestors, self) if path.segments.contains(any) =>
                  complete(acls.list(path, ancestors, self).runToFuture)
                case (Some(rev), false, self)                            =>
                  complete(acls.fetch(path, rev, self).toSingleList(path).runToFuture)
                case (_, false, self)                                    =>
                  complete(acls.fetch(path, self).toSingleList(path).runToFuture)
                case (_, true, self)                                     =>
                  complete(acls.list(path, ancestors = true, self).runToFuture)
              }
            )
          }
        }
      }
    }
}

object AclsRoutes {

  implicit private[routes] class TaskResourceACLSyntax(private val value: Task[ResourceOpt]) extends AnyVal {
    def toSingleList(path: Path): Task[AccessControlLists] =
      value.map {
        case None                                              => AccessControlLists.empty
        case Some(acl) if acl.value == AccessControlList.empty => AccessControlLists.empty
        case Some(acl)                                         => AccessControlLists(path -> acl)
      }
  }

  sealed private[routes] trait PatchAcl

  private[routes] object PatchAcl {

    final case class SubtractAcl(acl: AccessControlList) extends PatchAcl
    final case class AppendAcl(acl: AccessControlList)   extends PatchAcl

    implicit val patchAclDecoder: Decoder[PatchAcl] =
      Decoder.instance { hc =>
        for {
          tpe   <- hc.get[String]("@type")
          acl   <- hc.value.as[AccessControlList]
          patch <- tpe match {
                     case "Append"   => Right(AppendAcl(acl))
                     case "Subtract" => Right(SubtractAcl(acl))
                     case _          => Left(DecodingFailure("@type field must have Append or Subtract value", hc.history))
                   }
        } yield patch
      }
  }
}
