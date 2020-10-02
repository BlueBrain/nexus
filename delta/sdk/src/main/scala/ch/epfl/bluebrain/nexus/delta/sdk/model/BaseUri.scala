package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

/**
  * The BaseUri holds information about the platform endpoint.
  *
  * @param base   the base [[Uri]]
  * @param prefix an optional path prefix to be appended to the ''base''
  */
final case class BaseUri private (base: Uri, prefix: Option[Label]) {

  /**
    * The platform endpoint with base / prefix
    */
  val endpoint: Uri = prefix.fold(base)(p => base / p.value)
}

object BaseUri {

  /**
    * Construct a [[BaseUri]] without a prefix.
    */
  def apply(base: Uri): BaseUri = new BaseUri(base, None)

  /**
    * Construct a [[BaseUri]] with a prefix.
    */
  def apply(base: Uri, prefix: Label): BaseUri = new BaseUri(base, Some(prefix))
}
