package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model._
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidCustomMetadata, InvalidMultipartFieldName}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.syntax.{EncoderOps, KeyOps}
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

    def entityWithDescription(description: String) = {
      createEntity("file", NoContentType, Some("file.custom"), description = Some(description))
    }

    def entityWithName(name: String) = {
      createEntity("file", NoContentType, Some("file.custom"), name = Some(name))
    }

    def createEntity(
        bodyPart: String,
        contentType: ContentType,
        filename: Option[String],
        keywords: Map[String, Json] = Map.empty,
        description: Option[String] = None,
        name: Option[String] = None
    ) =
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
        filename.map("filename"                       -> _) ++
          Option.when(!metadata.isEmpty())("metadata" -> metadata.noSpaces)
      )
    }

    "be extracted with the default content type" in {
      val entity = createEntity("file", NoContentType, Some("filename"))

      val UploadedFileInformation(filename, _, _, _, contentType, contents) =
        extractor(iri, entity, 250, None).accepted

      filename shouldEqual "filename"
      contentType shouldEqual `application/octet-stream`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the custom media type from the config" in {
      val entity                                                            = createEntity("file", NoContentType, Some("file.custom"))
      val UploadedFileInformation(filename, _, _, _, contentType, contents) =
        extractor(iri, entity, 2000, None).accepted

      filename shouldEqual "file.custom"
      contentType shouldEqual customContentType
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the akka detection from the extension" in {
      val entity = createEntity("file", NoContentType, Some("file.txt"))

      val UploadedFileInformation(filename, _, _, _, contentType, contents) =
        extractor(iri, entity, 250, None).accepted
      filename shouldEqual "file.txt"
      contentType shouldEqual `text/plain(UTF-8)`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with the provided content type header" in {
      val entity                                                            = createEntity("file", `text/plain(UTF-8)`, Some("file.custom"))
      val UploadedFileInformation(filename, _, _, _, contentType, contents) =
        extractor(iri, entity, 2000, None).accepted
      filename shouldEqual "file.custom"
      contentType shouldEqual `text/plain(UTF-8)`
      consume(contents.dataBytes) shouldEqual content
    }

    "be extracted with a description" in {
      val entity                                              = entityWithDescription("this file is cool")
      val UploadedFileInformation(_, _, description, _, _, _) = extractor(iri, entity, 2000, None).accepted
      description shouldEqual Some("this file is cool")
    }

    "be extracted with a name" in {
      val entity                                       = entityWithName("File One")
      val UploadedFileInformation(_, _, _, name, _, _) = extractor(iri, entity, 2000, None).accepted
      name shouldEqual Some("File One")
    }

    "be extracted with keywords" in {
      val entity                                           = entityWithKeywords("key" := "value")
      val UploadedFileInformation(_, keywords, _, _, _, _) = extractor(iri, entity, 2000, None).accepted
      keywords shouldEqual Map(Label.unsafe("key") -> "value")
    }

    "fail to be extracted if the custom user metadata has invalid keywords" in {
      val entity = entityWithKeywords(KeyThatIsTooLong := "value")
      val rej    = extractor(iri, entity, 2000, None).rejectedWith[InvalidCustomMetadata]
      println(rej)
    }

    "fail to be extracted if no file part exists found" in {
      val entity = createEntity("other", NoContentType, None)
      extractor(iri, entity, 250, None).rejectedWith[InvalidMultipartFieldName]
    }

    "fail to be extracted if payload size is too large" in {
      val entity = createEntity("other", `text/plain(UTF-8)`, None)

      extractor(iri, entity, 10, None).rejected shouldEqual FileTooLarge(10L, None)
    }
  }
}
