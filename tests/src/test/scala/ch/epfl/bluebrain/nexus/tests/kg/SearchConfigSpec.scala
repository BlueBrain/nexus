package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Resources}
import io.circe.Json
import monix.bio.Task
import org.scalatest.Assertion

import java.time.Instant
import concurrent.duration._

class SearchConfigSpec extends BaseSpec {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(config.patience * 2, 300.millis)

  private val orgId    = genId()
  private val projId1  = genId()
  private val id1      = s"$orgId/$projId1"
  private val projects = List(id1)

  private val neuronMorphologyId   = "https://bbp.epfl.ch/data/neuron-morphology"
  private val neuronDensityId      = "https://bbp.epfl.ch/data/neuron-density"
  private val traceId              = "https://bbp.epfl.ch/data/trace"
  private val curatedTraceId       = "https://bbp.epfl.ch/data/curated-trace"
  private val unassessedTraceId    = "https://bbp.epfl.ch/data/unassessed-trace"
  private val layerThicknessId     = "https://bbp.epfl.ch/data/layer-thickness"
  private val boutonDensityId      = "https://bbp.epfl.ch/data/bouton-density"
  private val simulationCampaignId = "https://bbp.epfl.ch/data/simulation-campaign"
  private val simulationId         = "https://bbp.epfl.ch/data/simulation"

  // the resources that should appear in the search index
  private val mainResources  = List(
    "/kg/search/patched-cell.json",
    "/kg/search/trace.json",
    "/kg/search/curated-trace.json",
    "/kg/search/unassessed-trace.json",
    "/kg/search/neuron-morphology.json",
    "/kg/search/neuron-density.json",
    "/kg/search/layer-thickness.json",
    "/kg/search/bouton-density.json",
    "/kg/search/data/simulations/simulation-campaign-configuration.json",
    "/kg/search/data/simulations/simulation-campaign-execution.json",
    "/kg/search/data/simulations/simulation-campaign.json",
    "/kg/search/data/simulations/simulation.json",
    "/kg/search/data/simulations/analysis-report-simulation.json"
  )
  private val otherResources = List(
    "/kg/search/article.json",
    "/kg/search/org.json",
    "/kg/search/license.json",
    "/kg/search/activity.json",
    "/kg/search/protocol.json",
    "/kg/search/person.json"
  )
  private val allResources   = otherResources ++ mainResources

  override def beforeAll(): Unit = {
    super.beforeAll()

    val searchSetup = for {
      _ <- aclDsl.cleanAcls(Rick)
      _ <- aclDsl.addPermission("/", Rick, Organizations.Create)
      _ <- adminDsl.createOrganization(orgId, orgId, Rick)
      _ <- adminDsl.createProject(orgId, projId1, kgDsl.projectJson(path = "/kg/projects/bbp.json", name = id1), Rick)

      _ <- aclDsl.addPermission(s"/$orgId", Rick, Resources.Read)
      _ <- aclDsl.addPermission(s"/$orgId/$projId1", Rick, Resources.Read)

      _ <- postResource("/kg/search/neuroshapes.json")
      _ <- postResource("/kg/search/bbp-neuroshapes.json")
      _ <- allResources.traverseTap(postResource)
    } yield ()

    searchSetup.accepted
  }

  "search" should {

    "index all data" in {
      eventually {
        deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
          response.status shouldEqual StatusCodes.OK
          val sources = Json.fromValues(body.findAllByKey("_source"))
          sources.asArray.get.size shouldBe mainResources.size
        }
      }
    }

    "have the correct name property from schema:name" in {
      val query = queryField(neuronMorphologyId, "name")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "name": "sm080522a1-5_idA" }"""
      }
    }

    "have the correct name property from rdfs:label" in {
      val query = queryField(neuronDensityId, "name")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "name": "Neuron density: CA1" }"""
      }
    }

    "have the correct name property from skos:prefLabel" in {
      val query = queryField(traceId, "name")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "name": "S1J_L6_IPC_cADpyr_2" }"""
      }
    }

    "have the correct description property" in {
      val query = queryField(neuronMorphologyId, "description")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "description": "This is a resource description." }"""
      }
    }

    "have the correct createdAt property" in {
      val query = queryField(neuronMorphologyId, "createdAt")
      assertOneSource(query) { json =>
        assert(isInstant(json, "createdAt"))
      }
    }

    "have the correct updatedAt property" in {
      val query = queryField(neuronMorphologyId, "updatedAt")
      assertOneSource(query) { json =>
        assert(isInstant(json, "updatedAt"))
      }
    }

    "have the correct project property" in {
      val query    = queryField(neuronMorphologyId, "project")
      val expected =
        json"""
        {
          "project" : {
            "@id": "http://delta:8080/v1/projects/$id1",
            "identifier" : "http://delta:8080/v1/projects/$id1",
            "label" : "$id1"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct license property" in {
      val query    = queryField(neuronMorphologyId, "license")
      val expected =
        json"""
        {
          "license" : {
            "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/licenses/97521f71-605d-4f42-8f1b-c37e742a30bf",
            "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/licenses/97521f71-605d-4f42-8f1b-c37e742a30bf",
            "label" : "SSCX Portal Data Licence final v1.0"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct brain region property" in {
      val query    = queryField(neuronMorphologyId, "brainRegion")
      val expected =
        json"""
          {
            "brainRegion" : {
              "@id" : "http://purl.obolibrary.org/obo/UBERON_0008933",
              "idLabel" : "http://purl.obolibrary.org/obo/UBERON_0008933|primary somatosensory cortex",
              "identifier" : "http://purl.obolibrary.org/obo/UBERON_0008933",
              "label" : "primary somatosensory cortex"
            }
          }
          """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct layer property" in {
      val query    = queryField(neuronMorphologyId, "layer")
      val expected =
        json"""{
             "layer" : [
              {
                "@id" : "http://purl.obolibrary.org/obo/UBERON_0005391",
                "idLabel" : "http://purl.obolibrary.org/obo/UBERON_0005391|layer 2",
                "identifier" : "http://purl.obolibrary.org/obo/UBERON_0005391",
                "label" : "layer 2"
              }
            ]
          }
          """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }.accepted
    }

    "have the correct coordinatedInBrainAtlas property" in {
      val query    = queryField(neuronMorphologyId, "coordinatesInBrainAtlas")
      val expected =
        json"""
        {
          "coordinatesInBrainAtlas" : {
            "valueX" : "7124.0",
            "valueY" : "1040.05",
            "valueZ" : "5129.275"
          }
        }
        """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }.accepted
    }

    "have the correct species property" in {
      val query    = queryField(neuronMorphologyId, "subjectSpecies")
      val expected =
        json"""
          {
            "subjectSpecies" : {
              "@id" : "http://purl.obolibrary.org/obo/NCBITaxon_10116",
              "identifier" : "http://purl.obolibrary.org/obo/NCBITaxon_10116",
              "label" : "Rattus norvegicus"
            }
          }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct distribution property" in {
      val query    = queryField(neuronMorphologyId, "distribution")
      val expected =
        json"""
          {
          "distribution" : [
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/b6cb3fbd-73d6-47c7-bf23-3a993b326afa",
              "contentSize" : 522260,
              "contentUrl" : "https://bbp.epfl.ch/nexus/v1/files/public/sscx/b6cb3fbd-73d6-47c7-bf23-3a993b326afa",
              "encodingFormat" : "application/swc",
              "label" : "sm080522a1-5_idA.swc"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }
    "have the correct contributor property" in {
      val query    = queryField(neuronMorphologyId, "contributors")
      val expected =
        json"""
        {
          "contributors" : [
            {
              "@id" : "https://www.grid.ac/institutes/grid.5333.6",
              "@type" : [
                "http://schema.org/Organization",
                "http://www.w3.org/ns/prov#Agent"
              ],
              "idLabel": "https://www.grid.ac/institutes/grid.5333.6|École Polytechnique Fédérale de Lausanne",
              "label": "École Polytechnique Fédérale de Lausanne"
            },
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/d3a0dafe-f8ed-4b4d-bd90-93d64baf63a1",
              "@type" : [
                "http://www.w3.org/ns/prov#Agent",
                "http://schema.org/Person"
              ],
              "idLabel" : "https://bbp.epfl.ch/neurosciencegraph/data/d3a0dafe-f8ed-4b4d-bd90-93d64baf63a1|John Doe",
              "label" : "John Doe",
              "affiliation": "École Polytechnique Fédérale de Lausanne"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct generation property" in {
      val query    = queryField(neuronMorphologyId, "generation")
      val expected =
        json"""
        {
          "generation" : {
            "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/6af3b9db-fa85-4190-93c7-156a715c5aa3",
            "protocol" : [
              {
                "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/2f8814d6-a40f-4377-a675-e164816c5d73",
                "label" : "Reconstruction and Simulation of Neocortical Microcircuitry",
                "propertyID" : "doi",
                "value" : "https://doi.org/10.1016/j.cell.2015.09.029"
              }
            ],
            "startedAt" : "2015-01-21T00:00:00.000Z",
            "endedAt" : "2015-05-01T00:00:00.000Z"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct derivation property" in {
      val query    = queryField(neuronMorphologyId, "derivation")
      val expected =
        json"""
        {
          "derivation" : [
            {
              "@type" : [
                "https://neuroshapes.org/PatchedCell",
                "http://www.w3.org/ns/prov#Entity"
              ],
              "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/22e90788-b089-4014-83ab-206d0d3af71d",
              "label" : "sm080522a1-5_idA"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct mType property" in {
      val query    = queryField(traceId, "mType")
      val expected =
        json"""
        {
          "mType" : {
            "@id" : "http://uri.interlex.org/base/ilx_0381373",
            "idLabel" : "http://uri.interlex.org/base/ilx_0381373|L6_IPC",
            "identifier" : "http://uri.interlex.org/base/ilx_0381373",
            "label" : "L6_IPC"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }
    "have the correct eType property" in {
      val query    = queryField(traceId, "eType")
      val expected =
        json"""
        {
          "eType" : {
            "@id" : "http://bbp.epfl.ch/neurosciencegraph/ontologies/etypes/cADpyr",
            "idLabel" : "http://bbp.epfl.ch/neurosciencegraph/ontologies/etypes/cADpyr|cADpyr",
            "identifier" : "http://bbp.epfl.ch/neurosciencegraph/ontologies/etypes/cADpyr",
            "label" : "cADpyr"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have no curated field if there is no curated/unassessed annotation" in {
      val query = queryField(traceId, "curated")
      assertEmpty(query)
    }

    "have curated field true if curated" in {
      val query = queryField(curatedTraceId, "curated")

      assertOneSource(query) { json =>
        json shouldBe json"""{ "curated": true }"""
      }
    }

    "have curated field false if unassessed" in {
      val query = queryField(unassessedTraceId, "curated")

      assertOneSource(query) { json =>
        json shouldBe json"""{ "curated": false }"""
      }
    }

    "have the correct image property" in {
      val query    = queryField(traceId, "image")
      val expected =
        json"""
        {
          "image" : [
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/58342dff-8034-4b53-933b-1c034cdc8180",
              "about" : "https://neuroshapes.org/StimulationTrace",
              "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/58342dff-8034-4b53-933b-1c034cdc8180",
              "repetition" : 1,
              "stimulusType" : "step_2"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct subject age (exact value) property" in {
      val query    = queryField(layerThicknessId, "subjectAge")
      val expected =
        json"""
        {
          "subjectAge" : {
            "label" : "56 days Post-natal",
            "period" : "Post-natal",
            "unit" : "days",
            "value" : 56
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct subject weight (exact value) property" in {
      val query    = queryField(layerThicknessId, "subjectWeight")
      val expected =
        json"""
        {
          "subjectWeight" : {
            "label" : "37.1 g",
            "unit" : "g",
            "value" : 37.1
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct subject age (min/max case) property" in {
      val query    = queryField(neuronDensityId, "subjectAge")
      val expected =
        json"""
        {
          "subjectAge" : {
            "label" : "9 to 10 weeks Post-natal",
            "period" : "Post-natal",
            "unit" : "weeks",
            "minValue" : 9,
            "maxValue" : 10
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct subject weight (min/max case) property" in {
      val query    = queryField(neuronDensityId, "subjectWeight")
      val expected =
        json"""
        {
          "subjectWeight" : {
            "label" : "37.1 to 40 g",
            "unit" : "g",
            "minValue" : 37.1,
            "maxValue" : 40
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct neuron density property" in {
      val query    = queryField(neuronDensityId, "neuronDensity")
      val expected =
        json"""
        {
          "neuronDensity" : {
            "label" : "35200.0 neurons/mm³ (N = 5)",
            "nValue" : 5,
            "unit" : "neurons/mm³",
            "value" : 35200.0
          }
        }
          """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct layer thickness property" in {
      val query    = queryField(layerThicknessId, "layerThickness")
      val expected =
        json"""
        {
          "layerThickness" : {
            "label" : "250 µm (N = 1)",
            "nValue" : 1,
            "unit" : "µm",
            "value" : 250
          }
        }
         """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct bouton density property" in {
      val query    = queryField(boutonDensityId, "boutonDensity")
      val expected =
        json"""
        {
          "boutonDensity" : {
            "label" : "0.1212 boutons/μm",
            "unit" : "boutons/μm",
            "value" : 0.1212
          }
        }
        """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct series property" in {
      val query    = queryField(boutonDensityId, "series")
      val expected =
        json"""
        {
          "series" : [
            {
              "statistic" : "mean",
              "unit" : "boutons/μm",
              "value" : 0.1212
            }
          ]
        }
       """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct source property" in {
      val query    = queryField(neuronMorphologyId, "source")
      val expected =
        json"""
        {
          "source" : [
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/scholarlyarticles/1",
              "@type" : [
                "http://schema.org/ScholarlyArticle",
                "http://www.w3.org/ns/prov#Entity"
              ],
              "identifier" : [
                {
                  "propertyID" : "doi",
                  "value" : "10.1093/cercor/bht274"
                },
                {
                  "propertyID" : "PMID",
                  "value" : 24108800
                }
              ],
              "title" : "Cell type-specific effects of adenosine on cortical neurons."
            }
          ]
        }
      """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct metadata property" in { pending }
    "have the correct detailed circuit property" in { pending }
    "have the correct simulation campaign config property" in { pending }
    "have the correct sType property" in {
      // there are no resources with this field yet
      pending
    }

    "have the correct configuration for a simulation campaign" in {
      val query    = queryField(simulationCampaignId, "config")
      val expected =
        json"""{
                "config" : {
                  "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/simulation-campaign-configuration",
                  "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/simulation-campaign-configuration",
                  "name" : "SBO Simulation campaign test"
                }
               }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct status for a simulation campaign" in {
      val query    = queryField(simulationCampaignId, "status")
      val expected = json"""{ "status" : "Running" }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct parameter for a simulation campaign" in {
      val query    = queryField(simulationCampaignId, "parameter")
      val expected =
        json"""{
               "parameter": {
                 "attrs" : {
                   "blue_config_template" : "simulation.tmpl",
                   "circuit_config" : "/path/to/circuit_config.json",
                   "duration" : 1000,
                   "path_prefix" : "/home/simulations",
                   "user_target" : "target.json"
                 },
                 "coords" : {
                    "depol_stdev_mean_ratio" : [ 0.2, 0.3, 0.4 ],
                    "sample" : [ "small", "medium", "big" ],
                    "seed" : 273986
                  }
               }
         }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct campaign for a simulation" in {
      val query    = queryField(simulationId, "campaign")
      val expected =
        json"""{
           "campaign" : {
             "@id" : "https://bbp.epfl.ch/data/simulation-campaign",
             "identifier" : "https://bbp.epfl.ch/data/simulation-campaign",
             "name" : "Simulation campaign"
           }
        }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct status for a simulation" in {
      val query    = queryField(simulationId, "status")
      val expected = json"""{ "status" : "Done" }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct startedAt for a simulation" in {
      val query    = queryField(simulationId, "startedAt")
      val expected = json"""{ "startedAt" : "2023-07-05T11:00:00.000Z" }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct endedAt for a simulation" in {
      val query    = queryField(simulationId, "endedAt")
      val expected = json"""{ "endedAt" : "2023-07-12T15:00:00.000Z" }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "have the correct parameter for a simulation" in {
      val query    = queryField(simulationId, "parameter")
      val expected =
        json"""{
               "parameter": {
                 "coords" : {
                   "depol_stdev_mean_ratio" : 0.4,
                   "sample" : "medium",
                   "seed" : 273986
                 }
               }
           }"""

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

  }

  /**
    * Defines an ES query that searches for the document with the provided id and limits the resulting source to just
    * the requested field
    */
  private def queryField(id: String, field: String) =
    jsonContentOf("/kg/search/id-query.json", "id" -> id, "field" -> field)

  /** Post a resource across all defined projects in the suite */
  private def postResource(resourcePath: String): Task[List[Assertion]] = {
    val json = jsonContentOf(resourcePath)
    projects.parTraverse { project =>
      for {
        _ <- deltaClient.post[Json](s"/resources/$project/_/", json, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }
  }

  /**
    * Queries ES using the provided query. Asserts that there is only on result in _source. Runs the provided assertion
    * on the _source.
    */
  private def assertOneSource(query: Json)(assertion: Json => Assertion): Task[Assertion] =
    eventually {
      deltaClient.post[Json]("/search/query", query, Rick) { (body, response) =>
        response.status shouldEqual StatusCodes.OK
        val sources = Json.fromValues(body.findAllByKey("_source"))
        val source  = sources.hcursor.downArray.as[Json]
        val actual  = source.getOrElse(Json.Null)

        sources.asArray.value.size shouldBe 1
        assertion(actual)
      }
    }

  private def assertEmpty(query: Json): Task[Assertion] =
    assertOneSource(query)(j => assert(j == json"""{ }"""))

  /** Check that a given field in the json can be parsed as [[Instant]] */
  private def isInstant(json: Json, field: String) =
    json.hcursor.downField(field).as[Instant].isRight

}
