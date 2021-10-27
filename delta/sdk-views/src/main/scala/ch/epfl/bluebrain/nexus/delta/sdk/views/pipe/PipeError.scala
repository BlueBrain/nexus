package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

sealed abstract class PipeError(val reason: String, details: Option[String] = None) extends Exception {
  override def fillInStackTrace(): PipeError = this
  override def getMessage: String            = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object PipeError {

  final case class PipeNotFound(name: String)
      extends PipeError(
        s"Pipe $name can not be found."
      )

  final case class InvalidConfig(name: String, details: String)
      extends PipeError(
        s"The config provided for the pipe $name is invalid.",
        Some(details)
      )

}
