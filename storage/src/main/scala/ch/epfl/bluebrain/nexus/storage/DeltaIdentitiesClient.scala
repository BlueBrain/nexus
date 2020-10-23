package ch.epfl.bluebrain.nexus.storage

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.util.ByteString
import cats.effect.{ContextShift, Effect, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient.Identity._
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient._
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClientError.IdentitiesSerializationError
import ch.epfl.bluebrain.nexus.storage.config.DeltaClientConfig
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport.{DecodingFailures => AccDecodingFailures}
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, HCursor}

import scala.concurrent.ExecutionContext

class DeltaIdentitiesClient[F[_]](config: DeltaClientConfig)(implicit F: Effect[F], as: ActorSystem)
    extends JsonLdCirceSupport {

  private val um: FromEntityUnmarshaller[Caller]      = unmarshaller[Caller]
  implicit private val ec: ExecutionContext           = as.dispatcher
  implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)

  def apply()(implicit credentials: Option[AccessToken]): F[Caller] =
    credentials match {
      case Some(token) =>
        execute(Get(Uri(config.identitiesIri.toString)).addCredentials(OAuth2BearerToken(token.value)))
      case None        =>
        F.pure(Caller.anonymous)
    }

  private def execute(req: HttpRequest): F[Caller] = {
    IO.fromFuture(IO(Http().singleRequest(req))).to[F].flatMap { resp =>
      if (resp.status.isSuccess())
        IO.fromFuture(IO(um(resp.entity))).to[F].recoverWith {
          case err: AccDecodingFailures => F.raiseError(IdentitiesSerializationError(err.getMessage))
          case err: Error               => F.raiseError(IdentitiesSerializationError(err.getMessage))
        }
      else
        IO.fromFuture(IO(resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)))
          .to[F]
          .flatMap { err => F.raiseError(DeltaIdentitiesClientError.unsafe(resp.status, err)) }
    }
  }

}

object DeltaIdentitiesClient {

  /**
    * The client caller. It contains the subject and the list of identities (which contains the subject again)
    *
    * @param subject    the identity that performed the call
    * @param identities the set of other identities associated to the ''subject''. E.g.: groups, anonymous, authenticated
    */
  final case class Caller(subject: Subject, identities: Set[Identity])

  object Caller {

    /**
      * An anonymous caller
      */
    val anonymous: Caller = Caller(Anonymous: Subject, Set[Identity](Anonymous))

    implicit final val callerDecoder: Decoder[Caller] =
      Decoder.instance { cursor =>
        cursor
          .get[Set[Identity]]("identities")
          .flatMap { identities =>
            identities.collectFirst { case u: User => u } orElse identities.collectFirst { case Anonymous =>
              Anonymous
            } match {
              case Some(subject: Subject) => Right(Caller(subject, identities))
              case _                      =>
                val pos = cursor.downField("identities").history
                Left(DecodingFailure("Unable to find a subject in the collection of identities", pos))
            }
          }
      }
  }

  /**
    * A data structure which represents an access token
    *
    * @param value the token value
    */
  final case class AccessToken(value: String)

  /**
    * Base enumeration type for identity classes.
    */
  sealed trait Identity extends Product with Serializable

  object Identity {

    /**
      * Base enumeration type for subject classes.
      */
    sealed trait Subject extends Identity

    sealed trait Anonymous extends Subject

    /**
      * The Anonymous subject
      */
    final case object Anonymous extends Anonymous

    /**
      * The User subject
      *
      * @param subject unique user name
      * @param realm   user realm
      */
    final case class User(subject: String, realm: String) extends Subject

    /**
      * The Group identity
      *
      * @param group the group
      * @param realm group realm
      */
    final case class Group(group: String, realm: String) extends Identity

    /**
      * The Authenticated identity
      *
      * @param realm the realm
      */
    final case class Authenticated(realm: String) extends Identity

    private def decodeAnonymous(hc: HCursor): Result[Subject] =
      hc.get[String]("@type").flatMap {
        case "Anonymous" => Right(Anonymous)
        case _           => Left(DecodingFailure("Cannot decode Anonymous Identity", hc.history))
      }

    private def decodeUser(hc: HCursor): Result[Subject] =
      (hc.get[String]("subject"), hc.get[String]("realm")).mapN { case (subject, realm) =>
        User(subject, realm)
      }

    private def decodeGroup(hc: HCursor): Result[Identity] =
      (hc.get[String]("group"), hc.get[String]("realm")).mapN { case (group, realm) =>
        Group(group, realm)
      }

    private def decodeAuthenticated(hc: HCursor): Result[Identity] =
      hc.get[String]("realm").map(Authenticated)

    private val attempts =
      List[HCursor => Result[Identity]](decodeAnonymous, decodeUser, decodeGroup, decodeAuthenticated)

    implicit val identityDecoder: Decoder[Identity] =
      Decoder.instance { hc =>
        attempts.foldLeft(Left(DecodingFailure("Unexpected", hc.history)): Result[Identity]) {
          case (acc @ Right(_), _) => acc
          case (_, f)              => f(hc)
        }
      }
  }

}
