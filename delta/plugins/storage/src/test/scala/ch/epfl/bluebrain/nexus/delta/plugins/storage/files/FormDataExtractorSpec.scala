package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model._
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileDescription
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.scalatest.EitherValues
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class FormDataExtractorSpec
    extends TestKit(ActorSystem("FormDataExtractorSpec"))
    with AnyWordSpecLike
    with Matchers
    with CatsIOValues
    with CatsRunContext
    with EitherValues
    with AkkaSourceHelpers {

  "A Form Data HttpEntity" should {

    val uuid                   = UUID.randomUUID()
    implicit val sc: Scheduler = Scheduler.global
    implicit val uuidF: UUIDF  = UUIDF.fixed(uuid)

    val content = "file content"
    val iri     = iri"http://localhost/file"

    val customMediaType   = MediaType.parse("application/custom").rightValue
    val customContentType = ContentType(customMediaType, () => HttpCharsets.`UTF-8`)
    val mediaTypeDetector = MediaTypeDetectorConfig(Map("custom" -> customMediaType))
    val extractor         = FormDataExtractor(mediaTypeDetector)

    def createEntity(bodyPart: String, contentType: ContentType, filename: Option[String]) =
      Multipart
        .FormData(
          Multipart.FormData
            .BodyPart(bodyPart, HttpEntity(contentType, content.getBytes), filename.map("filename" -> _).toMap)
        )
        .toEntity()

    "be extracted with the default content type" in {
      val entity = createEntity("file", NoContentType, Some("file"))

      val expectedDescription         = FileDescription(uuid, "file", Some(`application/octet-stream`))
      val (description, resultEntity) = extractor(iri, entity, 179, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "be extracted with the custom media type from the config" in {
      val entity                      = createEntity("file", NoContentType, Some("file.custom"))
      val expectedDescription         = FileDescription(uuid, "file.custom", Some(customContentType))
      val (description, resultEntity) = extractor(iri, entity, 2000, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "be extracted with the akka detection from the extension" in {
      val entity = createEntity("file", NoContentType, Some("file.txt"))

      val expectedDescription         = FileDescription(uuid, "file.txt", Some(`text/plain(UTF-8)`))
      val (description, resultEntity) = extractor(iri, entity, 179, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "be extracted with the provided content type header" in {
      val entity                      = createEntity("file", `text/plain(UTF-8)`, Some("file.custom"))
      val expectedDescription         = FileDescription(uuid, "file.custom", Some(`text/plain(UTF-8)`))
      val (description, resultEntity) = extractor(iri, entity, 2000, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
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
