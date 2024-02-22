package ch.epfl.bluebrain.nexus.tests.kg.files.model

import akka.http.scaladsl.model.MediaType.NotCompressible
import akka.http.scaladsl.model.{ContentType, ContentTypes, MediaType}

final case class FileInput(
    fileId: String,
    filename: String,
    ct: ContentType,
    contents: String,
    keywords: Map[String, String]
)

object FileInput {
  val emptyFileContent       = ""
  val jsonFileContent        = """{ "initial": ["is", "a", "test", "file"] }"""
  val updatedJsonFileContent = """{ "updated": ["is", "a", "test", "file"] }"""

  val emptyTextFile                  =
    FileInput("empty", "empty", ContentTypes.`text/plain(UTF-8)`, emptyFileContent, Map("brainRegion" -> "cortex"))
  val jsonFileNoContentType          =
    FileInput(
      "attachment.json",
      "attachment.json",
      ContentTypes.NoContentType,
      jsonFileContent,
      Map("brainRegion" -> "cerebellum")
    )
  val updatedJsonFileWithContentType =
    jsonFileNoContentType.copy(contents = updatedJsonFileContent, ct = ContentTypes.`application/json`)
  val textFileNoContentType          = FileInput(
    "attachment2",
    "attachment2",
    ContentTypes.NoContentType,
    "text file",
    Map("brainRegion" -> "hippocampus")
  )
  val textFileWithContentType        =
    FileInput(
      "attachment3",
      "attachment2",
      ContentTypes.`application/octet-stream`,
      "text file",
      Map("brainRegion" -> "hippocampus")
    )

  val customBinaryContent =
    FileInput(
      "custom-binary",
      "custom-binary",
      ContentType.Binary(MediaType.applicationBinary("obj", NotCompressible)),
      "text file",
      Map("brainRegion" -> "hippocampus")
    )
}
