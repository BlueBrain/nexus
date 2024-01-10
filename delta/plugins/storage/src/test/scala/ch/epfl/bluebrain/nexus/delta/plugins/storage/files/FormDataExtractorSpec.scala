package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model._
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.FileUserMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileDescription
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName, InvalidUserMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.{Json, JsonObject}
import io.circe.syntax.KeyOps

import java.util.UUID

class FormDataExtractorSpec
    extends TestKit(ActorSystem("FormDataExtractorSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers {

  "A Form Data HttpEntity" should {

    val uuid                  = UUID.randomUUID()
    implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

    val content = "file content"
    val iri     = iri"http://localhost/file"

    val customMediaType   = MediaType.parse("application/custom").rightValue
    val customContentType = ContentType(customMediaType, () => HttpCharsets.`UTF-8`)
    val mediaTypeDetector = MediaTypeDetectorConfig(Map("custom" -> customMediaType))
    val extractor         = FormDataExtractor(mediaTypeDetector)
    val KeyThatIsTooLong  =
      "this-key-is-too-long-to-be-a-label-lalalalalalalaalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalalaalalalla"

    def entityWithMetadata(metadata: JsonObject) = {
      createEntity("file", NoContentType, Some("file.custom"), Some(metadata))
    }

    def createEntity(
        bodyPart: String,
        contentType: ContentType,
        filename: Option[String],
        metadata: Option[JsonObject] = None
    ) =
      Multipart
        .FormData(
          Multipart.FormData
            .BodyPart(bodyPart, HttpEntity(contentType, content.getBytes), dispositionParameters(filename, metadata))
        )
        .toEntity()

    def dispositionParameters(filename: Option[String], metadata: Option[JsonObject]): Map[String, String] = {
      Map.from(filename.map("filename" -> _) ++ metadata.map("metadata" -> _.toJson.noSpaces))
    }

    def metadataWithKeywords(keywords: (String, Json)*): JsonObject = {
      JsonObject("keywords" := JsonObject.fromIterable(keywords))
    }

    "be extracted with the default content type" in {
      val entity = createEntity("file", NoContentType, Some("file"))

      val expectedDescription                           = FileDescription(uuid, "file", Some(`application/octet-stream`))
      val FileInformation(_, description, resultEntity) = extractor(iri, entity, 179, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "be extracted with the custom media type from the config" in {
      val entity                                        = createEntity("file", NoContentType, Some("file.custom"))
      val expectedDescription                           = FileDescription(uuid, "file.custom", Some(customContentType))
      val FileInformation(_, description, resultEntity) = extractor(iri, entity, 2000, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "be extracted with the akka detection from the extension" in {
      val entity = createEntity("file", NoContentType, Some("file.txt"))

      val expectedDescription                           = FileDescription(uuid, "file.txt", Some(`text/plain(UTF-8)`))
      val FileInformation(_, description, resultEntity) = extractor(iri, entity, 179, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "be extracted with the provided content type header" in {
      val entity                                        = createEntity("file", `text/plain(UTF-8)`, Some("file.custom"))
      val expectedDescription                           = FileDescription(uuid, "file.custom", Some(`text/plain(UTF-8)`))
      val FileInformation(_, description, resultEntity) = extractor(iri, entity, 2000, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "be extracted with custom user metadata" in {
      val entity                          = entityWithMetadata(metadataWithKeywords("key" := "value"))
      val FileInformation(metadata, _, _) = extractor(iri, entity, 2000, None).accepted
      metadata.value shouldEqual FileUserMetadata(Map(Label.unsafe("key") -> "value"))
    }

    "fail to be extracted if the custom user metadata has invalid keywords" in {
      val entity = entityWithMetadata(metadataWithKeywords(KeyThatIsTooLong := "value"))
      extractor(iri, entity, 2000, None).rejectedWith[InvalidUserMetadata]
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
