package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.*
import akka.http.scaladsl.model.{ContentType, HttpEntity, Multipart}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.MediaType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileDataHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

class FormDataExtractorSpec
    extends TestKit(ActorSystem("FormDataExtractorSpec"))
    with CatsEffectSpec
    with FileDataHelpers {

  "A Form Data HttpEntity" should {

    val content = "file content"

    val customMediaType   = MediaType("application", "custom")
    val mediaTypeDetector = MediaTypeDetectorConfig(Map("custom" -> customMediaType))
    val extractor         = FormDataExtractor(new MediaTypeDetector(mediaTypeDetector))

    def createEntity(
        bodyPart: String,
        mediaType: Option[MediaType],
        filename: Option[String],
        keywords: Map[String, Json] = Map.empty,
        description: Option[String] = None,
        name: Option[String] = None
    ) = {
      val contentType = mediaType.fold(NoContentType: ContentType)(MediaType.toAkkaContentType)
      Multipart
        .FormData(
          Multipart.FormData
            .BodyPart(
              bodyPart,
              HttpEntity(contentType, content.getBytes),
              dispositionParameters(filename, keywords, description, name)
            )
        )
        .toEntity()
    }

    def dispositionParameters(
        filename: Option[String],
        keywords: Map[String, Json],
        description: Option[String],
        name: Option[String]
    ): Map[String, String] = {

      val metadata = JsonObject(
        "name"        -> name.asJson,
        "description" -> description.asJson,
        "keywords"    -> JsonObject.fromMap(keywords).toJson
      ).toJson

      Map.from(
        filename.map("filename" -> _) ++
          Option.when(!metadata.isEmpty())("metadata" -> metadata.noSpaces)
      )
    }

    "be extracted with the default content type" in {
      val entity = createEntity("file", None, Some("filename"))

      val UploadedFileInformation(filename, mediaType, contents) =
        extractor(entity, 250).accepted

      filename shouldEqual "filename"
      mediaType.value shouldEqual MediaType.`application/octet-stream`
      consume(contents).accepted shouldEqual content
    }

    "be extracted with the custom media type from the detector" in {
      val entity                                                 = createEntity("file", None, Some("file.custom"))
      val UploadedFileInformation(filename, mediaType, contents) = extractor(entity, 2000).accepted

      filename shouldEqual "file.custom"
      mediaType.value shouldEqual customMediaType
      consume(contents).accepted shouldEqual content
    }

    "be extracted with the akka detection from the extension" in {
      val entity = createEntity("file", None, Some("file.txt"))

      val UploadedFileInformation(filename, contentType, contents) = extractor(entity, 250).accepted
      filename shouldEqual "file.txt"
      contentType.value shouldEqual MediaType.`text/plain`
      consume(contents).accepted shouldEqual content
    }

    "be extracted with the default filename when none is provided" in {
      val entity = createEntity("file", None, None)

      val filename = extractor(entity, 250).accepted.filename
      filename shouldEqual "file"
    }

    "be extracted with the default filename when an empty string is provided" in {
      val entity = createEntity("file", None, Some(""))

      val filename = extractor(entity, 250).accepted.filename
      filename shouldEqual "file"
    }

    "be extracted with the provided content type header" in {
      val entity                                                 = createEntity("file", Some(MediaType.`text/plain`), Some("file.custom"))
      val UploadedFileInformation(filename, mediaType, contents) = extractor(entity, 2000).accepted
      filename shouldEqual "file.custom"
      mediaType.value shouldEqual MediaType.`text/plain`
      consume(contents).accepted shouldEqual content
    }

    "fail to be extracted if no file part exists found" in {
      val entity = createEntity("other", None, None)
      extractor(entity, 250).rejectedWith[InvalidMultipartFieldName.type]
    }

    "fail to be extracted if payload size is too large" in {
      val entity = createEntity("other", Some(MediaType.`text/plain`), None)
      extractor(entity, 10).rejected shouldEqual FileTooLarge(10L)
    }
  }
}
