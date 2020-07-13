package ch.epfl.bluebrain.nexus.storage.client.types

import akka.http.scaladsl.model.Uri
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

/**
  * Link file request.
  *
  * @param source    the relative location of the file/dir
  */
final private[client] case class LinkFile(source: Uri.Path)

private[client] object LinkFile {
  implicit val linkFileDec: Decoder[LinkFile] = deriveDecoder[LinkFile]
  implicit val linkFileEnc: Encoder[LinkFile] = deriveEncoder[LinkFile]
}
