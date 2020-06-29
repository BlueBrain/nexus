package ch.epfl.bluebrain.nexus.iam.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.directives.RealmDirectives._
import ch.epfl.bluebrain.nexus.iam.realms.{Realms, Resource, ResourceMetadata}
import ch.epfl.bluebrain.nexus.iam.routes.RealmsRoutes._
import ch.epfl.bluebrain.nexus.iam.types.ResourceF.resourceMetaEncoder
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Contexts.{iamCtxUri, resourceCtxUri, searchCtxUri}
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
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
class RealmsRoutes(acls: Acls[Task], realms: Realms[Task])(implicit http: HttpConfig)
    extends AuthDirectives(acls, realms) {

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

  implicit private val resourceListEncoder: Encoder[List[Resource]] =
    Encoder.encodeJson.contramap[List[Resource]] { l =>
      Json
        .obj(
          nxv.total.prefix   -> Json.fromInt(l.size),
          nxv.results.prefix -> Json.arr(l.map(r => resourceEncoder(r).removeKeys("@context")): _*)
        )
        .addContext(resourceCtxUri)
        .addContext(iamCtxUri)
        .addContext(searchCtxUri)
    }

  def routes: Route =
    pathPrefix("realms") {
      concat(
        (get & searchParams & pathEndOrSingleSlash) { params =>
          operationName(s"/${http.prefix}/realms") {
            extractCaller { implicit c => complete(realms.list(params).runToFuture) }
          }
        },
        (label & pathEndOrSingleSlash) { id =>
          operationName(s"/${http.prefix}/realms/{}") {
            extractCaller {
              implicit c =>
                concat(
                  put {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        entity(as[Realm]) {
                          case Realm(name, openIdConfig, logo) =>
                            complete(realms.update(id, rev, name, openIdConfig, logo).runToFuture)
                        }
                      case None      =>
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
                      case None      =>
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
}

object RealmsRoutes {

  final private[routes] case class Realm(name: String, openIdConfig: Url, logo: Option[Url])
  private[routes] object Realm {
    implicit val realmDecoder: Decoder[Realm] = deriveDecoder[Realm]
  }
}
