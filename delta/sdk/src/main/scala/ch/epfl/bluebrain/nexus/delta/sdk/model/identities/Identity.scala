package ch.epfl.bluebrain.nexus.delta.sdk.model.identities

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.error.FormatError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatErrors.{IllegalIdentityIriFormatError, IllegalSubjectIriFormatError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._

/**
  * Parent type for unique identities as recognized by the system. A client usually has multiple identities with the
  * exception where it performs calls without including an auth token (in which case his only identity is Anonymous).
  */
sealed trait Identity extends Product with Serializable {

  /**
    * A [[Identity]] expressed as an Iri
    *
    * @param base
    *   the platform [[BaseUri]]
    */
  def id(implicit base: BaseUri): Iri
}

object Identity {

  /**
    * An identity that has a realm
    */
  sealed trait IdentityRealm extends Identity {

    /**
      * @return
      *   the realm of the identity
      */
    def realm: Label
  }

  /**
    * Parent type for identities that represent a uniquely identified caller.
    */
  sealed trait Subject extends Identity {

    /**
      * A [[Subject]] expressed as an Iri
      *
      * @param base
      *   the platform [[BaseUri]]
      */
    def id(implicit base: BaseUri): Iri
  }

  object Subject {

    /**
      * Attempts to convert an ''iri'' into a [[Subject]].
      *
      * @param iri
      *   the iri
      * @param base
      *   the base uri
      */
    final def unsafe(iri: Iri)(implicit base: BaseUri): Either[FormatError, Subject] =
      iri.stripPrefix(base.iriEndpoint) match {
        case "/anonymous"              => Right(Anonymous)
        case userRegex(realm, subject) => Right(User(subject, Label.unsafe(realm)))
        case _                         => Left(IllegalSubjectIriFormatError(iri))
      }

    implicit def subjectFromCaller(implicit caller: Caller): Subject = caller.subject

    implicit val orderingSubject: Ordering[Subject] = Ordering.by(_.id(BaseUri("http://localhost", None)))
  }

  /**
    * The Anonymous type.
    */
  type Anonymous = Anonymous.type

  /**
    * The Anonymous singleton identity.
    */
  final case object Anonymous extends Subject {
    override def id(implicit base: BaseUri): Iri = base.iriEndpoint / "anonymous"
  }

  /**
    * A user identity. It represents a unique person or a service account.
    *
    * @param subject
    *   the subject name (usually the preferred_username claim)
    * @param realm
    *   the associated realm that asserts this identity
    */
  final case class User(subject: String, realm: Label) extends Subject with IdentityRealm {
    override def id(implicit base: BaseUri): Iri = base.iriEndpoint / "realms" / realm.value / "users" / subject
  }

  /**
    * A group identity. It asserts that the caller belongs to a certain group of callers.
    *
    * @param group
    *   the group name (asserted by one entry in the groups claim)
    * @param realm
    *   the associated realm that asserts this identity
    */
  final case class Group(group: String, realm: Label) extends IdentityRealm {

    def id(implicit base: BaseUri): Iri =
      base.iriEndpoint / "realms" / realm.value / "groups" / group

  }

  /**
    * An authenticated identity is an arbitrary caller that has provided a valid AuthToken issued by a specific realm.
    *
    * @param realm
    *   the realm that asserts this identity
    */
  final case class Authenticated(realm: Label) extends IdentityRealm {
    def id(implicit base: BaseUri): Iri =
      base.iriEndpoint / "realms" / realm.value / "authenticated"
  }

  /**
    * Attempts to convert an ''iri'' into an [[Identity]].
    *
    * @param iri
    *   the iri
    * @param base
    *   the base uri
    */
  final def unsafe(iri: Iri)(implicit base: BaseUri): Either[FormatError, Identity] =
    iri.stripPrefix(base.iriEndpoint) match {
      case "/anonymous"              => Right(Anonymous)
      case userRegex(realm, subject) => Right(User(subject, Label.unsafe(realm)))
      case groupRegex(realm, group)  => Right(Group(group, Label.unsafe(realm)))
      case authenticatedRegex(realm) => Right(Authenticated(Label.unsafe(realm)))
      case _                         => Left(IllegalIdentityIriFormatError(iri))
    }

  private[identities] val userRegex          = s"^/realms\\/(${Label.regex})\\/users\\/([^\\/]+)$$".r
  private[identities] val groupRegex         = s"^/realms\\/(${Label.regex})\\/groups\\/([^\\/]+)$$".r
  private[identities] val authenticatedRegex = s"^/realms\\/(${Label.regex})\\/authenticated$$".r

  implicit private[Identity] val config: Configuration = Configuration.default.withDiscriminator("@type")

  val persistIdentityDecoder: Encoder.AsObject[Identity] = deriveConfiguredEncoder[Identity]

  implicit def identityEncoder(implicit base: BaseUri): Encoder[Identity] = {
    val enc = deriveConfiguredEncoder[Identity]
    Encoder.encodeJson.contramap { ident => enc(ident) deepMerge Json.obj("@id" -> ident.id.asJson) }
  }

  private def decodeAnonymous(hc: HCursor): Result[Subject] =
    hc.get[String]("@type").flatMap {
      case "Anonymous" => Right(Anonymous)
      case _           => Left(DecodingFailure("Cannot decode Anonymous Identity", hc.history))
    }

  private def decodeUser(hc: HCursor): Result[Subject] =
    (hc.get[String]("subject"), hc.get[Label]("realm")).mapN { case (subject, realm) =>
      User(subject, realm)
    }

  private def decodeGroup(hc: HCursor): Result[Identity] =
    (hc.get[String]("group"), hc.get[Label]("realm")).mapN { case (group, realm) =>
      Group(group, realm)
    }

  private def decodeAuthenticated(hc: HCursor): Result[Identity] =
    hc.get[Label]("realm").map(Authenticated)

  private val attempts        =
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

  implicit def subjectEncoder(implicit base: BaseUri): Encoder[Subject] =
    Encoder.encodeJson.contramap {
      identityEncoder.apply(_: Identity)
    }

  def subjectIdEncoder(implicit base: BaseUri): Encoder[Subject] =
    Encoder.encodeJson.contramap(_.id.asJson)

  def subjectIdDecoder(implicit base: BaseUri): Decoder[Subject] =
    Decoder[Iri].emap(iri =>
      Identity.unsafe(iri) match {
        case Right(s: Subject) => Right(s)
        case Left(err)         => Left(err.toString)
        case Right(_)          => Left(s"Identity '$iri' is not a subject")
      }
    )

  def identityIdEncoder(implicit base: BaseUri): Encoder[Identity] =
    Encoder.encodeJson.contramap(_.id.asJson)

  implicit val subjectDecoder: Decoder[Subject] = Decoder.instance { hc =>
    attemptsSubject.foldLeft(Left(DecodingFailure("Unexpected", hc.history)): Result[Subject]) {
      case (acc @ Right(_), _) => acc
      case (_, f)              => f(hc)
    }
  }
}
