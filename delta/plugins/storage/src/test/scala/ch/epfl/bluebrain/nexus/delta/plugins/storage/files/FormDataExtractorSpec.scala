package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model._
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidKeywords, InvalidMultipartFieldName}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.syntax.KeyOps
import io.circe.{Json, JsonObject}

class FormDataExtractorSpec
    extends TestKit(ActorSystem("FormDataExtractorSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers {

  "A Form Data HttpEntity" should {

    val content = "file content"
    val iri     = iri"http://localhost/file"

    val customMediaType   = MediaType.parse("application/custom").rightValue
    val customContentType = ContentType(customMediaType, () => HttpCharsets.`UTF-8`)
    val mediaTypeDetector = MediaTypeDetectorConfig(Map("custom" -> customMediaType))
    val extractor         = FormDataExtractor(mediaTypeDetector)
    val KeyThatIsTooLong  =
      "this-key-is-too-long-to-be-a-label-lalalalalalalaalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalaalalalla"

    def entityWithKeywords(keywords: (String, Json)*) = {
      createEntity("file", NoContentType, Some("file.custom"), keywords.toMap)
    }

    def createEntity(
        bodyPart: String,
        contentType: ContentType,
        filename: Option[String],
        keywords: Map[String, Json] = Map.empty
    ) =
      Multipart
        .FormData(
          Multipart.FormData
            .BodyPart(bodyPart, HttpEntity(contentType, content.getBytes), dispositionParameters(filename, keywords))
        )
        .toEntity()

    def dispositionParameters(filename: Option[String], keywords: Map[String, Json]): Map[String, String] = {
      Map.from(
        filename.map("filename"                     -> _) ++
          Option.when(keywords.nonEmpty)("keywords" -> JsonObject.fromMap(keywords).toJson.noSpaces)
      )
    }

    "be extracted with the default content type" in {
      val entity = createEntity("file", NoContentType, Some("filename"))

      val UploadedFileInformation(filename, keywords, contentType, contents) =
        extractor(iri, entity, 179, None).accepted

      filename shouldEqual "filename"
      keywords shouldEqual Map.empty
      contentType shouldEqual `application/octet-stream`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the custom media type from the config" in {
      val entity                                                             = createEntity("file", NoContentType, Some("file.custom"))
      val UploadedFileInformation(filename, keywords, contentType, contents) =
        extractor(iri, entity, 2000, None).accepted

      filename shouldEqual "file.custom"
      keywords shouldEqual Map.empty
      contentType shouldEqual customContentType
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the akka detection from the extension" in {
      val entity = createEntity("file", NoContentType, Some("file.txt"))

      val UploadedFileInformation(filename, keywords, contentType, contents) =
        extractor(iri, entity, 179, None).accepted
      filename shouldEqual "file.txt"
      keywords shouldEqual Map.empty
      contentType shouldEqual `text/plain(UTF-8)`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the provided content type header" in {
      val entity                                                             = createEntity("file", `text/plain(UTF-8)`, Some("file.custom"))
      val UploadedFileInformation(filename, keywords, contentType, contents) =
        extractor(iri, entity, 2000, None).accepted
      filename shouldEqual "file.custom"
      keywords shouldEqual Map.empty
      contentType shouldEqual `text/plain(UTF-8)`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with keywords" in {
      val entity                                     = entityWithKeywords("key" := "value")
      val UploadedFileInformation(_, keywords, _, _) = extractor(iri, entity, 2000, None).accepted
      keywords shouldEqual Map(Label.unsafe("key") -> "value")
    }

    "fail to be extracted if the custom user metadata has invalid keywords" in {
      val entity = entityWithKeywords(KeyThatIsTooLong := "value")
      extractor(iri, entity, 2000, None).rejectedWith[InvalidKeywords]
    }

    "fail to be extracted if no file part exists found" in {
      val entity = createEntity("other", NoContentType, None)
      extractor(iri, entity, 179, None).rejectedWith[InvalidMultipartFieldName]
    }

    "fail to be extracted if payload size is too large" in {
      val entity = createEntity("other", `text/plain(UTF-8)`, None)

      extractor(iri, entity, 10, None).rejected shouldEqual FileTooLarge(10L, None)
    }
  }
}
