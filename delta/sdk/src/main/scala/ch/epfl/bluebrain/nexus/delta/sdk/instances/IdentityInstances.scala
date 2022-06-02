package ch.epfl.bluebrain.nexus.delta.sdk.instances

import ch.epfl.bluebrain.nexus.delta.kernel.error.FormatError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatErrors.{IllegalIdentityIriFormatError, IllegalSubjectIriFormatError}
import ch.epfl.bluebrain.nexus.delta.sdk.instances.IdentityInstances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.{IriDecoder, IriEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

trait IdentityInstances {

  implicit final val identityIriDecoder: IriDecoder[Identity] = new IriDecoder[Identity] {
    override def apply(iri: Iri)(implicit base: BaseUri): Either[FormatError, Identity] =
      iri.stripPrefix(base.iriEndpoint) match {
        case "/anonymous"              => Right(Anonymous)
        case userRegex(realm, subject) => Right(User(subject, Label.unsafe(realm)))
        case groupRegex(realm, group)  => Right(Group(group, Label.unsafe(realm)))
        case authenticatedRegex(realm) => Right(Authenticated(Label.unsafe(realm)))
        case _                         => Left(IllegalIdentityIriFormatError(iri))
      }
  }

  implicit final val identityIriEncoder: IriEncoder[Identity] = new IriEncoder[Identity] {
    override def apply(value: Identity)(implicit base: BaseUri): Iri = value match {
      case Anonymous                           => base.iriEndpoint / "anonymous"
      case Authenticated(realm)                => base.iriEndpoint / "realms" / realm.value / "authenticated"
      case Group(group, realm)                 => base.iriEndpoint / "realms" / realm.value / "groups" / group
      case User(subject: String, realm: Label) => base.iriEndpoint / "realms" / realm.value / "users" / subject
    }
  }

  @nowarn("cat=unused")
  private val baseEncoder: Encoder.AsObject[Identity] = {
    implicit val config: Configuration = Configuration.default.withDiscriminator("@type")
    deriveConfiguredEncoder[Identity]
  }

  implicit def identityEncoder(implicit base: BaseUri): Encoder[Identity] = {
    val idEncoder: Encoder[Identity] = IriEncoder.jsonEncoder[Identity]
    Encoder.encodeJson.contramap { ident => baseEncoder(ident) deepMerge Json.obj("@id" -> idEncoder(ident)) }
  }

  implicit def subjectEncoder(implicit base: BaseUri): Encoder[Subject] =
    Encoder.encodeJson.contramap {
      identityEncoder.apply(_: Identity)
    }

  implicit val subjectIriDecoder: IriDecoder[Subject] = new IriDecoder[Subject] {
    override def apply(iri: Iri)(implicit base: BaseUri): Either[FormatError, Subject] =
      iri.stripPrefix(base.iriEndpoint) match {
        case "/anonymous"              => Right(Anonymous)
        case userRegex(realm, subject) => Right(User(subject, Label.unsafe(realm)))
        case _                         => Left(IllegalSubjectIriFormatError(iri))
      }
  }

  implicit def subjectFromCaller(implicit caller: Caller): Subject = caller.subject
}

object IdentityInstances extends IdentityInstances {

  private val userRegex          = s"^/realms\\/(${Label.regex})\\/users\\/([^\\/]+)$$".r
  private val groupRegex         = s"^/realms\\/(${Label.regex})\\/groups\\/([^\\/]+)$$".r
  private val authenticatedRegex = s"^/realms\\/(${Label.regex})\\/authenticated$$".r

}
