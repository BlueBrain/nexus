package ch.epfl.bluebrain.nexus.iam.types

import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._

/**
  * Base enumeration type for identity classes.
  */
sealed trait Identity extends Product with Serializable {
  def id(implicit http: HttpConfig): AbsoluteIri
}

object Identity {

  /**
    * Base enumeration type for subject classes.
    */
  sealed trait Subject extends Identity

  sealed trait Anonymous extends Subject

  /**
    * The Anonymous subject
    */
  final case object Anonymous extends Anonymous {
    def id(implicit http: HttpConfig): AbsoluteIri =
      http.publicIri + (http.prefix / "anonymous")
  }

  /**
    * The User subject
    *
    * @param subject unique user name
    * @param realm   user realm
    */
  final case class User(subject: String, realm: String) extends Subject {
    def id(implicit http: HttpConfig): AbsoluteIri =
      http.publicIri + (http.prefix / "realms" / realm / "users" / subject)

  }

  /**
    * The Group identity
    *
    * @param group the group
    * @param realm group realm
    */
  final case class Group(group: String, realm: String) extends Identity {
    def id(implicit http: HttpConfig): AbsoluteIri =
      http.publicIri + (http.prefix / "realms" / realm / "groups" / group)
  }

  /**
    * The Authenticated identity
    *
    * @param realm the realm
    */
  final case class Authenticated(realm: String) extends Identity {
    def id(implicit http: HttpConfig): AbsoluteIri =
      http.publicIri + (http.prefix / "realms" / realm / "authenticated")
  }

  implicit private[Identity] val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit def identityEncoder(implicit http: HttpConfig): Encoder[Identity] = {
    val enc = deriveConfiguredEncoder[Identity]
    Encoder.encodeJson.contramap { ident => enc(ident) deepMerge Json.obj("@id" -> ident.id.asJson) }
  }

  private def decodeAnonymous(hc: HCursor): Result[Subject] =
    hc.get[String]("@type").flatMap {
      case "Anonymous" => Right(Anonymous)
      case _           => Left(DecodingFailure("Cannot decode Anonymous Identity", hc.history))
    }

  private def decodeUser(hc: HCursor): Result[Subject] =
    (hc.get[String]("subject"), hc.get[String]("realm")).mapN {
      case (subject, realm) => User(subject, realm)
    }

  private def decodeGroup(hc: HCursor): Result[Identity] =
    (hc.get[String]("group"), hc.get[String]("realm")).mapN {
      case (group, realm) => Group(group, realm)
    }

  private def decodeAuthenticated(hc: HCursor): Result[Identity] =
    hc.get[String]("realm").map(Authenticated)

  private val attempts =
    List[HCursor => Result[Identity]](decodeAnonymous, decodeUser, decodeGroup, decodeAuthenticated)
  private val attemptsSubject = List[HCursor => Result[Subject]](decodeAnonymous, decodeUser)

  implicit val identityDecoder: Decoder[Identity] = {
    Decoder.instance { hc =>
      attempts.foldLeft(Left(DecodingFailure("Unexpected", hc.history)): Result[Identity]) {
        case (acc @ Right(_), _) => acc
        case (_, f)              => f(hc)
      }
    }
  }

  implicit def subjectEncoder(implicit http: HttpConfig): Encoder[Subject] = Encoder.encodeJson.contramap {
    identityEncoder.apply(_: Identity)
  }

  def subjectIdEncoder(implicit http: HttpConfig): Encoder[Subject] =
    Encoder.encodeJson.contramap(_.id.asJson)

  implicit val subjectDecoder: Decoder[Subject] = Decoder.instance { hc =>
    attemptsSubject.foldLeft(Left(DecodingFailure("Unexpected", hc.history)): Result[Subject]) {
      case (acc @ Right(_), _) => acc
      case (_, f)              => f(hc)
    }
  }

  object JsonLd {
    implicit def subjectAsIriEncoder(implicit httpConfig: HttpConfig): Encoder[Subject] =
      subjectIdEncoder(httpConfig)
  }
}
