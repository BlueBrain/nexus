package ai.senscience.nexus.tests.kg.files

import ai.senscience.nexus.tests.Identity.Anonymous
import ai.senscience.nexus.tests.Identity.storages.Coyote
import ai.senscience.nexus.tests.Optics.filterMetadataKeys
import ai.senscience.nexus.tests.kg.files.S3DelegationFileSpec.DelegationResponse
import ai.senscience.nexus.tests.kg.files.model.FileInput
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Optics}
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import ch.epfl.bluebrain.nexus.testkit.scalatest.FileMatchers.{digest as digestField, filename as filenameField}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.jawn.parseByteBuffer
import io.circe.syntax.KeyOps
import io.circe.{Decoder, Json}
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp

import java.nio.ByteBuffer
import java.util.Base64

class S3DelegationFileSpec extends BaseIntegrationSpec with S3ClientFixtures {

  private val bucket = genId()

  private val orgId      = genId()
  private val projId     = genId()
  private val projectRef = s"$orgId/$projId"
  private val storageId  = "https://bluebrain.github.io/nexus/vocabulary/defaultS3Storage"

  implicit private val s3Client: S3AsyncClientOp[IO] = createS3Client.accepted

  override def beforeAll(): Unit = {
    super.beforeAll()

    val payload = jsonContentOf(
      "kg/storages/s3.json",
      "storageId" -> storageId,
      "bucket"    -> bucket
    )

    val setup =
      createBucket(bucket) >>
        createProjects(Coyote, orgId, projId) >>
        storagesDsl.createStorage(payload, projectRef).void

    setup.accepted
  }

  override def afterAll(): Unit = {
    cleanupBucket(bucket).accepted
    super.afterAll()
  }

  s"Delegate S3 file upload" should {

    val delegateUrl = s"/delegate/files/generate/$projectRef/?storage=${encodeUriPath(storageId)}"

    def delegateUriWithId(id: String) =
      s"/delegate/files/generate/$projectRef/${encodeUriPath(id)}?storage=${encodeUriPath(storageId)}"

    "fail to generate a delegation token without id without the appropriate permissions" in {
      val payload = json"""{ "filename":  "my-file.jpg"}"""
      deltaClient.post[Json](delegateUrl, payload, Anonymous) { expectForbidden }
    }

    "fail to generate a delegation token with id without the appropriate permissions" in {
      val id      = s"https://bbp.epfl.ch/data/${genString()}"
      val payload = json"""{ "filename":  "my-file.jpg"}"""
      deltaClient.put[Json](delegateUriWithId(id), payload, Anonymous) { expectForbidden }
    }

    "generate the payload by providing the id" in {
      val id      = s"https://bbp.epfl.ch/data/${genString()}"
      val payload = json"""{ "filename":  "my-file.jpg"}"""
      deltaClient.put[Json](delegateUriWithId(id), payload, Coyote) { case (jwsPayload, response) =>
        response.status shouldEqual StatusCodes.OK
        val delegateResponse = parseDelegationResponse(jwsPayload)
        delegateResponse.id shouldEqual id
        delegateResponse.targetLocation.bucket shouldEqual bucket
        delegateResponse.targetLocation.storageId shouldEqual storageId
        delegateResponse.project shouldEqual projectRef
      }
    }

    "succeed creating a file via delegation" in {
      val filename               = genString()
      val (name, desc, keywords) = (genString(), genString(), Json.obj(genString() := genString()))
      val metadata               =
        json"""
          {
            "name": "$name",
            "description": "$desc",
            "keywords": $keywords
          }
            """
      val payload                = Json.obj("filename" := filename, "metadata" -> metadata, "mediaType" := "image/png")

      for {
        jwsPayload      <- deltaClient.postAndReturn[Json](delegateUrl, payload, Coyote) { expectOk }
        delegateResponse = parseDelegationResponse(jwsPayload)
        _                = delegateResponse.targetLocation.bucket shouldEqual bucket
        _                = delegateResponse.project shouldEqual projectRef
        _               <- uploadLogoFileToS3(bucket, delegateResponse.targetLocation.path)
        _               <- deltaClient.put[Json](s"/delegate/files/submit", jwsPayload, Coyote) { expectCreated }
        encodedId        = encodeUriPath(delegateResponse.id)
        filename         = delegateResponse.targetLocation.path.split("/").last
        expectedMetadata = Json.obj("name" := name, "description" := desc, "_keywords" := keywords)
        assertion       <- deltaClient.get[Json](s"/files/$projectRef/$encodedId", Coyote) { (json, response) =>
                             response.status shouldEqual StatusCodes.OK
                             val expected =
                               delegatedFileResponse(delegateResponse, 1, filename, "image/png").deepMerge(expectedMetadata)
                             val actual   = filterMetadataKeys(json)
                             actual shouldEqual expected
                           }
      } yield assertion
    }

    "succeed updating a file via delegation and tag it" in {
      val fileId            = s"https://bluebrain.github.io/nexus/vocabulary/${genId()}"
      val filename          = genString()
      val updatedFilename   = genString()
      val originalFile      = FileInput(fileId, filename, ContentTypes.`text/plain(UTF-8)`, "test")
      val delegationPayload = Json.obj("filename" := updatedFilename, "mediaType" := "image/png")
      for {
        _               <- deltaClient.uploadFile(projectRef, "defaultS3Storage", originalFile, None) { expectCreated }(Coyote)
        delegationUrl    = s"${delegateUriWithId(fileId)}&rev=1&tag=delegated"
        jwsPayload      <- deltaClient.putAndReturn[Json](delegationUrl, delegationPayload, Coyote) { expectOk }
        delegateResponse = parseDelegationResponse(jwsPayload)
        _               <- uploadLogoFileToS3(bucket, delegateResponse.targetLocation.path)
        _               <- deltaClient.put[Json](s"/delegate/files/submit", jwsPayload, Coyote) { expectCreated }
        _               <- deltaClient.get[Json](s"/files/$projectRef/${encodeUriPath(fileId)}", Coyote) { (json, response) =>
                             response.status shouldEqual StatusCodes.OK
                             Optics._rev.getOption(json).value shouldEqual 2
                             json should have(filenameField(updatedFilename))
                             json should have(digestField("SHA-256", logoSha256HexDigest))
                           }
        _               <- deltaClient.get[Json](s"/files/$projectRef/${encodeUriPath(fileId)}?tag=delegated", Coyote) { expectOk }
      } yield (succeed)
    }
  }

  def parseDelegationResponse(jwsPayload: Json): DelegationResponse = {
    for {
      encodedPayload <- jwsPayload.hcursor.get[String]("payload")
      decodedPayload  = Base64.getDecoder.decode(encodedPayload)
      jsonPayload    <- parseByteBuffer(ByteBuffer.wrap(decodedPayload))
      resp           <- jsonPayload.as[DelegationResponse]
    } yield resp
  }.rightValue

  private def delegatedFileResponse(
      delegationResponse: DelegationResponse,
      rev: Int,
      filename: String,
      mediaType: String
  ): Json =
    jsonContentOf(
      "kg/files/delegated-metadata.json",
      replacements(
        Coyote,
        "id"          -> delegationResponse.id,
        "storageId"   -> storageId,
        "self"        -> fileSelf(projectRef, delegationResponse.id),
        "projId"      -> projectRef,
        "rev"         -> rev.toString,
        "digestValue" -> logoSha256HexDigest,
        "location"    -> delegationResponse.targetLocation.path,
        "filename"    -> filename,
        "mediaType"   -> mediaType
      )*
    )
}

object S3DelegationFileSpec {

  final case class TargetLocation(storageId: String, path: String, bucket: String)
  final case class DelegationResponse(id: String, project: String, targetLocation: TargetLocation)
  object DelegationResponse {
    implicit val targetLocationDecoder: Decoder[TargetLocation]         = deriveDecoder
    implicit val delegationResponseDecoder: Decoder[DelegationResponse] = deriveDecoder
  }

}
