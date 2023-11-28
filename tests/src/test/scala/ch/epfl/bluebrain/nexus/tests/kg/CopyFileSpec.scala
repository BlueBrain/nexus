package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model._
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics
import io.circe.Json
import io.circe.syntax.KeyOps
import org.scalatest.Assertion

trait CopyFileSpec { self: StorageSpec =>

  "Copying a json file to a different organization" should {

    def givenAProjectWithStorage(test: String => IO[Assertion]): IO[Assertion] = {
      val (proj, org) = (genId(), genId())
      val projRef     = s"$org/$proj"
      createProjects(Coyote, org, proj) >>
        createStorages(projRef) >>
        test(projRef)
    }

    "succeed" in {
      givenAProjectWithStorage { destProjRef =>
        val sourceFileId = "attachment.json"
        val destFileId   = "attachment2.json"
        val destFilename = genId()

        val payload = Json.obj(
          "destinationFilename" := destFilename,
          "sourceProjectRef"    := self.projectRef,
          "sourceFileId"        := sourceFileId
        )
        val uri     = s"/files/$destProjRef/$destFileId?storage=nxv:$storageId"

        for {
          json      <- deltaClient.putAndReturn[Json](uri, payload, Coyote) { (json, response) =>
                         (json, expectCreated(json, response))
                       }
          returnedId = Optics.`@id`.getOption(json).getOrElse(fail("could not find @id of created resource"))
          assertion <-
            deltaClient.get[ByteString](s"/files/$destProjRef/${UrlUtils.encode(returnedId)}", Coyote, acceptAll) {
              expectDownload(destFilename, ContentTypes.`application/json`, updatedJsonFileContent)
            }
        } yield assertion
      }
    }
  }
}
