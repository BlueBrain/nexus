package ai.senscience.nexus.tests.kg.files.model

import ai.senscience.nexus.tests.kg.files.model.FileInput.CustomMetadata
import akka.http.scaladsl.model.MediaType.NotCompressible
import akka.http.scaladsl.model.{ContentType, ContentTypes, MediaType}
import ch.epfl.bluebrain.nexus.testkit.Generators

final case class FileInput(
    fileId: String,
    filename: String,
    contentType: ContentType,
    contents: String,
    metadata: Option[CustomMetadata]
) {
  def contentLength: Long = contents.length.toLong
}

object FileInput extends Generators {

  def apply(fileId: String, filename: String, ct: ContentType, contents: String, metadata: CustomMetadata): FileInput =
    FileInput(fileId, filename, ct, contents, Some(metadata))

  def apply(fileId: String, filename: String, ct: ContentType, contents: String): FileInput =
    FileInput(fileId, filename, ct, contents, None)

  final case class CustomMetadata(name: Option[String], description: Option[String], keywords: Map[String, String])

  object CustomMetadata {
    def apply(name: String, description: String, keywords: Map[String, String]): CustomMetadata =
      CustomMetadata(Some(name), Some(description), keywords)
  }

  val emptyFileContent       = ""
  val jsonFileContent        = """{ "initial": ["is", "a", "test", "file"] }"""
  val updatedJsonFileContent = """{ "updated": ["is", "a", "test", "file"] }"""

  val emptyTextFile: FileInput =
    FileInput(
      "empty",
      "empty",
      ContentTypes.`text/plain(UTF-8)`,
      emptyFileContent,
      CustomMetadata("Ctx 1", "A cortex file", Map("brainRegion" -> "cortex"))
    )

  val jsonFileNoContentType: FileInput =
    FileInput(
      "attachment.json",
      "attachment.json",
      ContentTypes.NoContentType,
      jsonFileContent,
      CustomMetadata("Crb 2", "A cerebellum file", Map("brainRegion" -> "cerebellum"))
    )

  val updatedJsonFileWithContentType: FileInput =
    jsonFileNoContentType.copy(contents = updatedJsonFileContent, contentType = ContentTypes.`application/json`)

  val textFileNoContentType: FileInput = FileInput(
    "attachment2",
    "attachment2",
    ContentTypes.NoContentType,
    "text file",
    CustomMetadata("Hpc 3", "A hippocampus file", Map("brainRegion" -> "hippocampus"))
  )

  val textFileWithContentType: FileInput =
    FileInput(
      "attachment3",
      "attachment2",
      ContentTypes.`application/octet-stream`,
      "text file",
      CustomMetadata("Crb 4", "A cerebellum file", Map("brainRegion" -> "hippocampus"))
    )

  val customBinaryContent: FileInput =
    FileInput(
      "custom-binary",
      "custom-binary",
      ContentType.Binary(MediaType.applicationBinary("obj", NotCompressible)),
      "text file",
      CustomMetadata("custom-binary", "A custom file", Map("brainRegion" -> "hippocampus"))
    )

  def randomTextFile = {
    val randomString = genString()
    FileInput(
      randomString,
      randomString,
      ContentTypes.NoContentType,
      "text file"
    )
  }
}
