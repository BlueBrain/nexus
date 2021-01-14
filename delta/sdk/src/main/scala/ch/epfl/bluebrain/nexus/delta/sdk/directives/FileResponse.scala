package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

/**
  * A file response content
  *
  * @param filename    the filename
  * @param contentType the file content type
  * @param content     the file content
  */
final case class FileResponse(filename: String, contentType: ContentType, content: AkkaSource)
