package ch.epfl.bluebrain.nexus.realms

import io.circe.{Decoder, Encoder}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path

import scala.util.matching.Regex

/**
  * The realm label
  */
final case class RealmLabel private (value: String) {

  /**
    * Builds a Uri by suffixing the label to the provided base, separated by slash. If the provided ''base''
    * ends with a '/' the label is appended directly, otherwise a slash is injected before the label.
    */
  def toUri(base: Uri): Uri =
    base.copy(path = base.path.?/(value))

  /**
    * Builds a rooted path from the ''value''.
    */
  def rootedPath: Path =
    Path./ + value
}

object RealmLabel {
  private[realms] val regex: Regex = "[a-z0-9]{1,32}".r

  /**
    * Attempts to construct a ''RealmLabel'', applying the necessary format validation.
    */
  final def apply(value: String): Either[String, RealmLabel] = value match {
    case regex() => Right(new RealmLabel(value))
    case _       => Left(s"RealmLabel '$value' does not match pattern '${regex.regex}'")
  }

  /**
    * Attempts to construct a ''RealmLabel'', throwing an [[IllegalArgumentException]] if the provided value does not match
    * the expected label format.
    */
  @throws[IllegalArgumentException]("if the provided value does not match the expected RealmLabel format")
  final def unsafe(value: String): RealmLabel =
    apply(value).fold(err => throw new IllegalArgumentException(err), identity)

  implicit final val realmLabelEncoder: Encoder[RealmLabel] =
    Encoder.encodeString.contramap[RealmLabel](_.value)

  implicit final val realmLabelDecoder: Decoder[RealmLabel] =
    Decoder.decodeString.emap(RealmLabel.apply)
}
