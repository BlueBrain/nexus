package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, Multipart}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileDescription
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.{FileTooLarge, InvalidMultipartFieldName}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class FormDataExtractorSpec
    extends TestKit(ActorSystem("FormDataExtractorSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with AkkaSourceHelpers {

  "A Form Data HttpEntity" should {

    val uuid                   = UUID.randomUUID()
    implicit val sc: Scheduler = Scheduler.global
    implicit val uuidF: UUIDF  = UUIDF.fixed(uuid)

    val content = "file content"
    val iri     = iri"http://localhost/file"

    val extractor = FormDataExtractor.apply

    "be extracted" in {
      val entity =
        Multipart
          .FormData(
            Multipart.FormData
              .BodyPart("file", HttpEntity(`text/plain(UTF-8)`, content), Map("filename" -> "file.txt"))
          )
          .toEntity()

      val expectedDescription         = FileDescription(uuid, "file.txt", Some(`text/plain(UTF-8)`))
      val (description, resultEntity) = extractor(iri, entity, 179, None).accepted
      description shouldEqual expectedDescription
      consume(resultEntity.dataBytes) shouldEqual content
    }

    "fail to be extracted if no file part exists found" in {
      val entity =
        Multipart
          .FormData(Multipart.FormData.BodyPart("other", HttpEntity(`text/plain(UTF-8)`, content), Map.empty))
          .toEntity()

      extractor(iri, entity, 179, None).rejectedWith[InvalidMultipartFieldName]
    }

    "fail to be extracted if payload size is too large" in {
      val entity =
        Multipart
          .FormData(Multipart.FormData.BodyPart("other", HttpEntity(`text/plain(UTF-8)`, content), Map.empty))
          .toEntity()

      extractor(iri, entity, 10, None).rejected shouldEqual FileTooLarge(10L, None)
    }
  }
}
