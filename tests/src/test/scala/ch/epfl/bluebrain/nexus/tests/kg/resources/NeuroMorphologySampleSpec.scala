package ch.epfl.bluebrain.nexus.tests.kg.resources

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.listings.Bob
import io.circe.Json
import org.scalatest.Assertion

class NeuroMorphologySampleSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  private val neuronMorphologySample = "kg/schemas/bbp/sample-neuromorphology.json"

  private val noSchema               = "_"
  private val neuronMorphologySchema = UrlUtils.encode("https://neuroshapes.org/dash/neuronmorphology")

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- createProjects(Bob, orgId, projId)
      // Creating mandatory contexts
      _ <- postResource("kg/context/neuroshapes.json")
      _ <- postResource("kg/context/bbp-neuroshapes.json")
      _ <- postResource("kg/schemas/bbp/schema-context.json")
    } yield ()
    setup.accepted
  }

  "Creating a morphology" should {
    "succeed provisionning the mandatory schemas" in {
      val schemas = List(
        "common-labeledontologyentity.json",
        "common-unit.json",
        "common-quantitative-value.json",
        "common-subject.json",
        "common-language.json",
        "common-derivation.json",
        "common-propertyvalue.json",
        "common-identifier.json",
        "common-distribution.json",
        "common-generation.json",
        "common-typedlabeledontologyterm.json",
        "common-vector3d.json",
        "common-brainlocation.json",
        "common-license.json",
        "common-contribution.json",
        "common-invalidation.json",
        "common-entitywithoutannotationsubject.json",
        "common-annotation-selector.json",
        "common-annotation.json",
        "common-entity.json",
        "common-minds.json",
        "provshapes-collection.json",
        "common-collection.json",
        "neuromorphology-common.json",
        "neuromorphology-dash.json"
      )
      schemas.traverse { f => postSchema(s"kg/schemas/bbp/$f") }
    }

    "succeed creating the morphology without a schema" in {
      putResource("no-schema", noSchema, neuronMorphologySample)
    }

    "succeed creating the morphology with a schema" in {
      putResource("with-schema", neuronMorphologySchema, neuronMorphologySample)
    }

    "succeed updating the morphology with a schema" in {
      putResource("with-schema", 1, neuronMorphologySchema, neuronMorphologySample)
    }
  }

  private def postResource(resourcePath: String): IO[Assertion] =
    loader.jsonContentOf(resourcePath).flatMap { payload =>
      deltaClient.post[Json](s"/resources/$project/_/", payload, Bob) { expectCreated }
    }

  private def putResource(id: String, schema: String, resourcePath: String): IO[Assertion] =
    loader.jsonContentOf(resourcePath).flatMap { payload =>
      deltaClient.put[Json](s"/resources/$project/$schema/$id", payload, Bob) {
        expectCreated
      }
    }

  private def putResource(id: String, rev: Int, schema: String, resourcePath: String): IO[Assertion] =
    loader.jsonContentOf(resourcePath).flatMap { payload =>
      deltaClient.put[Json](s"/resources/$project/$schema/$id?rev=$rev", payload, Bob) {
        expectOk
      }
    }
  private def postSchema(schemaPath: String)                                                         =
    loader.jsonContentOf(schemaPath).flatMap { payload =>
      deltaClient.post[Json](s"/schemas/$project", payload, Bob) { expectCreated }
    }
}
