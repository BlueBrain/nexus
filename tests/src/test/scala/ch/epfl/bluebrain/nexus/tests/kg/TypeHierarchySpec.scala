package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.Ref
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.{typehierarchy, Anonymous}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.TypeHierarchy
import io.circe.Json
import io.circe.syntax.{EncoderOps, KeyOps}

class TypeHierarchySpec extends BaseIntegrationSpec {

  private val mapping =
    Json.obj(
      "mapping" := Json.obj(
        "https://schema.org/VideoGame" := Json.arr(
          "https://schema.org/SoftwareApplication".asJson,
          "https://schema.org/Thing".asJson
        )
      )
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclDsl.addPermission("/", typehierarchy.Writer, TypeHierarchy.Write).accepted
    deltaClient
      .post[Json]("/type-hierarchy", mapping, typehierarchy.Writer) { (_, _) =>
        assert(true)
      }
      .accepted
    ()
  }

  private val typeHierarchyRevisionRef = Ref.unsafe[IO, Int](0)

  "type hierarchy" should {

    "not be created without permissions" in {
      deltaClient.post[Json]("/type-hierarchy", mapping, Anonymous) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "be fetched" in {
      deltaClient.get[Json]("/type-hierarchy", Anonymous) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        typeHierarchyRevisionRef.set(json.hcursor.get[Int]("_rev").rightValue).accepted
        json.hcursor.get[Json]("mapping").rightValue shouldEqual mapping.hcursor.get[Json]("mapping").rightValue
      }
    }

    "not be updated without permissions" in {
      val rev = typeHierarchyRevisionRef.get.accepted

      deltaClient.put[Json](s"/type-hierarchy?rev=$rev", mapping, Anonymous) { (_, response) =>
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "be updated with write permissions" in {
      val rev = typeHierarchyRevisionRef.get.accepted

      deltaClient.put[Json](s"/type-hierarchy?rev=$rev", mapping, typehierarchy.Writer) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        json.hcursor.get[Int]("_rev").rightValue shouldEqual (rev + 1)
      }
    }

  }

}
