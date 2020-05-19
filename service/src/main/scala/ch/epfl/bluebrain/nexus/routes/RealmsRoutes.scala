package ch.epfl.bluebrain.nexus.routes

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.directives.AuthDirectives.authenticator
import ch.epfl.bluebrain.nexus.directives.RealmDirectives._
import ch.epfl.bluebrain.nexus.marshallers.instances._
import ch.epfl.bluebrain.nexus.realms.{Realms, Resource, ResourceMetadata}
import ch.epfl.bluebrain.nexus.routes.RealmsRoutes._
import ch.epfl.bluebrain.nexus.utils.Codecs._
import ch.epfl.bluebrain.nexus.auth.Caller
import ch.epfl.bluebrain.nexus.ResourceF.resourceMetaEncoder
import ch.epfl.bluebrain.nexus.syntax.all._
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * The realms routes.
  *
  * @param realms the realms api
  */
class RealmsRoutes(realms: Realms[Task])(implicit http: HttpConfig) {

  implicit private val resourceEncoder: Encoder[Resource] =
    Encoder.encodeJson.contramap { r =>
      resourceMetaEncoder.apply(r.discard) deepMerge r.value.fold(_.asJson, _.asJson)
    }

  implicit private val resourceMetadataEncoder: Encoder[ResourceMetadata] =
    Encoder.encodeJson.contramap { r =>
      resourceMetaEncoder.apply(r.discard) deepMerge Json.obj(
        nxv.label.prefix      -> Json.fromString(r.value._1.value),
        nxv.deprecated.prefix -> Json.fromBoolean(r.value._2)
      )
    }

  implicit private val resourceSeqEncoder: Encoder[Seq[Resource]] =
    Encoder.encodeJson.contramap[Seq[Resource]] { l =>
      Json
        .obj(
          nxv.total.prefix   -> Json.fromInt(l.size),
          nxv.results.prefix -> Json.arr(l.map(r => resourceEncoder(r).removeKeys("@context")): _*)
        )
//        .addContext(resourceCtxUri)
//        .addContext(iamCtxUri)
//        .addContext(searchCtxUri)
    }

  def routes: Route =
    pathPrefix("realms") {
      concat(
        (get & searchParams & pathEndOrSingleSlash) { params =>
          operationName(s"/${http.prefix}/realms") {
            caller { implicit c => complete(realms.list(params).runToFuture) }
          }
        },
        (realmLabel & pathEndOrSingleSlash) { id =>
          operationName(s"/${http.prefix}/realms/{}") {
            caller {
              implicit c =>
                concat(
                  put {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        entity(as[Realm]) {
                          case Realm(name, openIdConfig, logo) =>
                            complete(realms.update(id, rev, name, openIdConfig, logo).runToFuture)
                        }
                      case None =>
                        entity(as[Realm]) {
                          case Realm(name, openIdConfig, logo) =>
                            complete(realms.create(id, name, openIdConfig, logo).runWithStatus(StatusCodes.Created))
                        }
                    }
                  },
                  get {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        complete(realms.fetch(id, rev).runNotFound)
                      case None =>
                        complete(realms.fetch(id).runNotFound)
                    }
                  },
                  delete {
                    parameter("rev".as[Long]) { rev => complete(realms.deprecate(id, rev).runToFuture) }
                  }
                )
            }
          }
        }
      )
    }

  private def caller: Directive1[Caller] =
    authenticateOAuth2Async("*", authenticator(realms)).withAnonymousUser(Caller.anonymous)
}

object RealmsRoutes {

  final private[routes] case class Realm(name: String, openIdConfig: Uri, logo: Option[Uri])
  private[routes] object Realm {
    implicit val realmDecoder: Decoder[Realm] = deriveDecoder[Realm]
  }
}
