package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import io.circe.{Decoder, Encoder, Json}
import cats.syntax.all._

/**
  * Enumeration of possible encryption states
  */
sealed trait EncryptionState extends Product with Serializable

object EncryptionState {

  /**
    * An encrypted state
    */
  final case object Encrypted extends EncryptionState

  /**
    * An decrypted state
    */
  final case object Decrypted extends EncryptionState

  type Encrypted = Encrypted.type
  type Decrypted = Decrypted.type

  implicit val encryptedStateEncoder: Encoder[Encrypted] = Encoder.instance(_ => Json.Null)
  implicit val encryptedStateDecoder: Decoder[Encrypted] = Decoder.decodeOption[Unit].as(Encrypted)
  implicit val decryptedStateEncoder: Encoder[Decrypted] = Encoder.instance(_ => Json.Null)
  implicit val decryptedStateDecoder: Decoder[Decrypted] = Decoder.decodeOption[Unit].as(Decrypted)

}
