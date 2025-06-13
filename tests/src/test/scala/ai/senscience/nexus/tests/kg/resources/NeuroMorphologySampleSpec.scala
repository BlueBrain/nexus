package ai.senscience.nexus.tests.kg.resources

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.listings.Bob
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUriPath
import io.circe.Json
import io.circe.optics.JsonPath.root
import io.circe.syntax.KeyOps
import org.scalatest.Assertion

class NeuroMorphologySampleSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val project = s"$orgId/$projId"

  private val neuronMorphologyEntitySample  = "kg/schemas/bbp/sample-neuromorphology-entity.json"
  // Dataset is a complex shape most of our models inherits from so it is worth to be tested on its own
  private val neuronMorphologyDatasetSample = "kg/schemas/bbp/sample-neuronmorphology-dataset.json"

  private val noSchema                      = "_"
  private val neuronMorphologySchema        = "https://neuroshapes.org/dash/neuronmorphology"
  private val encodedNeuronMorphologySchema = encodeUriPath(neuronMorphologySchema)

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- createProjects(Bob, orgId, projId)
      // Creating mandatory contexts
      _ <- postResource("kg/context/neuroshapes.json")
      _ <- postResource("kg/context/bbp-neuroshapes.json")
      _ <- postResource("kg/schemas/bbp/schema-context.json")
      _ <- postResource("kg/schemas/bbp/brain-region-ontology.json")
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

    List(
      "neuronmorphology-entity"  -> neuronMorphologyEntitySample,
      "neuronmorphology-dataset" -> neuronMorphologyDatasetSample
    ).foreach { case (prefix, file) =>
      s"succeed creating the $prefix without a schema" in {
        putResource(s"$prefix-no-schema", noSchema, file)
      }

      s"succeed creating the $prefix with a schema" in {
        putResource(s"$prefix-with-schema", encodedNeuronMorphologySchema, file)
      }

      s"succeed updating the $prefix with a schema" in {
        putResource(s"$prefix-with-schema", 1, encodedNeuronMorphologySchema, file)
      }

      s"succeed generating the $prefix with the trial endpoint" in {
        trialResource(neuronMorphologySchema, file)
      }
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

  private def trialResource(schema: String, resourcePath: String) =
    loader.jsonContentOf(resourcePath).flatMap { resourcePayload =>
      val trialPayload = Json.obj(
        "schema"   := schema,
        "resource" := resourcePayload
      )
      deltaClient.post[Json](s"/trial/resources/$project/", trialPayload, Bob) { case (json, response) =>
        root.result.obj.getOption(json) shouldBe defined
        response.status shouldEqual StatusCodes.OK
      }
    }

  private def postSchema(schemaPath: String) =
    loader.jsonContentOf(schemaPath).flatMap { payload =>
      deltaClient.post[Json](s"/schemas/$project", payload, Bob) { expectCreated }
    }
}
