package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO

import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Resources}
import io.circe.Json
import org.scalactic.source.Position
import org.scalatest.Assertion

import java.time.Instant
import concurrent.duration._
import cats.implicits._

class SearchConfigIndexingSpec extends BaseIntegrationSpec {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(config.patience * 2, 300.millis)

  private val orgId    = genId()
  private val projId1  = genId()
  private val id1      = s"$orgId/$projId1"
  private val projects = List(id1)

  private val neuronMorphologyId          = "https://bbp.epfl.ch/data/neuron-morphology"
  private val neuronDensityId             = "https://bbp.epfl.ch/data/neuron-density"
  private val traceId                     = "https://bbp.epfl.ch/data/trace"
  private val curatedTraceId              = "https://bbp.epfl.ch/data/curated-trace"
  private val unassessedTraceId           = "https://bbp.epfl.ch/data/unassessed-trace"
  private val layerThicknessId            = "https://bbp.epfl.ch/data/layer-thickness"
  private val boutonDensityId             = "https://bbp.epfl.ch/data/bouton-density"
  private val simulationCampaignId        = "https://bbp.epfl.ch/data/simulation-campaign"
  private val simulationId                = "https://bbp.epfl.ch/data/simulation"
  private val synapseId                   = "https://bbp.epfl.ch/data/synapse"
  private val synapseTwoPathwaysId        = "https://bbp.epfl.ch/data/synapse-two-pathways"
  private val detailedCircuitId           = "https://bbp.epfl.ch/data/detailed-circuit"
  private val axonAnnotationId            = "https://bbp.epfl.ch/data/axon-annotation"
  private val apicalDendriteAnnotationId  = "https://bbp.epfl.ch/data/apical-dendrite-annotation"
  private val basalDendriteAnnotationId   = "https://bbp.epfl.ch/data/basal-dendrite-annotation"
  private val morphologyAnnotationId      = "https://bbp.epfl.ch/data/morphology-annotation"
  private val somaAnnotationId            = "https://bbp.epfl.ch/data/soma-annotation"

  // the resources that should appear in the search index
  private val mainResources  = List(
    "kg/search/patched-cell.json",
    "kg/search/trace.json",
    "kg/search/curated-trace.json",
    "kg/search/unassessed-trace.json",
    "kg/search/neuron-morphology.json",
    "kg/search/neuron-density.json",
    "kg/search/synapse.json",
    "kg/search/synapse-two-pathways.json",
    "kg/search/layer-thickness.json",
    "kg/search/bouton-density.json",
    "kg/search/detailed-circuit.json",
    "kg/search/data/simulations/simulation-campaign-configuration.json",
    "kg/search/data/simulations/simulation-campaign-execution.json",
    "kg/search/data/simulations/simulation-campaign.json",
    "kg/search/data/simulations/simulation.json",
    "kg/search/data/simulations/analysis-report-simulation.json"
    "kg/search/data/features/axon-annotation.json"
    "kg/search/data/features/apical-dendrite-annotation.json"
    "kg/search/data/features/basal-dendrite-annotation.json"
    "kg/search/data/features/morphology-annotation.json"
    "kg/search/data/features/soma-annotation.json"
  )
  private val otherResources = List(
    "kg/search/article.json",
    "kg/search/org.json",
    "kg/search/license.json",
    "kg/search/activity.json",
    "kg/search/protocol.json",
    "kg/search/person.json"
  )
  private val allResources   = otherResources ++ mainResources

  override def beforeAll(): Unit = {
    super.beforeAll()

    val searchSetup = for {
      _ <- aclDsl.cleanAcls(Rick)
      _ <- aclDsl.addPermission("/", Rick, Organizations.Create)
      _ <- adminDsl.createOrganization(orgId, orgId, Rick)
      _ <- adminDsl.createProjectWith(orgId, projId1, path = "kg/projects/bbp.json", name = id1, authenticated = Rick)

      _ <- aclDsl.addPermission(s"/$orgId", Rick, Resources.Read)
      _ <- aclDsl.addPermission(s"/$orgId/$projId1", Rick, Resources.Read)

      _ <- postResource("kg/search/neuroshapes.json")
      _ <- postResource("kg/search/bbp-neuroshapes.json")
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

    "have the correct createdBy property" in {
      val query = queryField(neuronMorphologyId, "createdBy")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "createdBy": "http://delta:8080/v1/realms/${Rick.realm.name}/users/${Rick.name}" }"""
      }
    }

    "have the correct updatedBy property" in {
      val query = queryField(neuronMorphologyId, "updatedBy")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "updatedBy": "http://delta:8080/v1/realms/${Rick.realm.name}/users/${Rick.name}" }"""
      }
    }

    "have the correct deprecated property" in {
      val query = queryField(neuronMorphologyId, "deprecated")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "deprecated": false }"""
      }
    }

    "have the correct self property" in {
      val query        = queryField(neuronMorphologyId, "_self")
      val expectedSelf = s"http://delta:8080/v1/resources/$orgId/$projId1/_/${neuronMorphologyId.replace("/", "%2F")}"
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "_self": "$expectedSelf" }"""
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

    "have the correct detailed circuit config path" in {
      val query = queryField(detailedCircuitId, "circuitConfigPath")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "circuitConfigPath": "file:///gpfs/bbp.cscs.ch/project/proj123/config.json" }"""
      }
    }

    "have the correct detailed circuit type " in {
      val query = queryField(detailedCircuitId, "circuitType")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "circuitType": "circuit type" }"""
      }
    }

    "have the correct detailed circuit base" in {
      val query = queryField(detailedCircuitId, "circuitBase")
      assertOneSource(query) { json =>
        json shouldEqual json"""{ "circuitBase": "file:///gpfs/bbp.cscs.ch/project/proj123/base" }"""
      }
    }

    "have the correct detailed circuit brain region" in {
      // TODO: check whether this is still used
      pending
    }

    "have the correct sType property" in {
      // TODO: there are no resources with this field yet
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

    "have the correct synaptic pathways when they are different" in {
      assertOneSource(queryDocument(synapseTwoPathwaysId)) { json =>
        json should have(
          field(
            "preSynapticPathway",
            json"""[
               {
                "@id": "http://bbp.epfl.ch/neurosciencegraph/ontologies/mtypes/TNJ_NwHgTKe1iv_XLR_0Yg",
                "about": "https://bbp.epfl.ch/neurosciencegraph/data/BrainCellType",
                "label": "SO_BS"
          },
              {
                "@id": "http://api.brain-map.org/api/v2/data/Structure/453",
                "about": "https://bbp.epfl.ch/neurosciencegraph/data/BrainRegion",
                "label": "Somatosensory areas",
                "notation": "SS"
              }
            ]"""
          )
        )
        json should have(
          field(
            "postSynapticPathway",
            json"""[
              {
                "@id": "http://api.brain-map.org/api/v2/data/Structure/454",
                "about": "https://bbp.epfl.ch/neurosciencegraph/data/OtherBrainRegion",
                "label": "Other somatosensory areas",
                "notation": "OSS"
              }
            ]"""
          )
        )
      }

    }

    "have the correct synaptic pathways when they are the same" in {
      val singlePathway =
        json"""
          [
            {
              "@id": "http://api.brain-map.org/api/v2/data/Structure/453",
              "about": "https://bbp.epfl.ch/neurosciencegraph/data/BrainRegion",
              "label": "Somatosensory areas",
              "notation": "SS"
            }
          ]"""

      assertOneSource(queryDocument(synapseId)) { json =>
        json should have(field("preSynapticPathway", singlePathway))
        json should have(field("postSynapticPathway", singlePathway))
      }
    }

    "aggregate presynaptic brain regions" in {
      val query                     = jsonContentOf("kg/search/synapse-agg.json")
      val preSynapticBrainRegionAgg =
        json"""{
          "preSynapticBrainRegions" : {
              "doc_count" : 3,
              "preSynapticBrainRegions" : {
                "doc_count" : 2,
                "label" : {
                  "buckets" : [
                    {
                      "doc_count" : 2,
                      "key" : "Somatosensory areas"
                    }
                  ],
                  "doc_count_error_upper_bound" : 0,
                  "sum_other_doc_count" : 0
                }
              }
            }
        }"""

      deltaClient.post[Json]("/search/query", query, Rick) { (json, _) =>
        aggregationIn(json) should contain(preSynapticBrainRegionAgg)
      }
    }

    "have the correct morphology mean length" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 1131.963147431612,
                 "unit" : "μm",
                 "label" : "Total Length",
                 "statistic" : "mean",
                 "compartment" : "NeuronMorphology",
                 }
               ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

    "have the correct soma radius" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 5.975075244861534,
                 "unit" : "μm",
                 "label" : "Soma Radius",
                 "statistic" : "mean"
                 "compartment" : "Soma"
                 }
               ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

   

    "have the correct axon length" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 52.48914,
                 "unit" : "μm",
                 "label" : "Total Length",
                 "statistic" : "mean",
                 "compartment": "Axon"
                 }
           ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

    "have the correct axon Strahler orders" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 0,
                 "unit" : "dimensionless",
                 "label" : "Section Strahler Orders",
                 "statistic": "max",
                 "compartment": "Axon"
                 }
           ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

    "have the correct apical dendrite length" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 103.02,
                 "unit" : "μm",
                 "label" : "Total Length",
                 "statistic": "mean",
                 "compartment": "ApicalDendrite"
                 }
           ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

    "have the correct apical dendrite Strahler orders" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 2.3,
                 "unit" : "dimensionless",
                 "label" : "Section Strahler Orders",
                 "statistic": "max",
                 "compartment": "ApicalDendrite"
                 }
           ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

   "have the correct apical partition asymmetry index" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 0
                 "unit" : "dimensionless",
                 "label" : "Partition Asymmetry",
                 "statistic": "mean",
                 "compartment": "ApicalDendrite"
                 }
           ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

    "have the correct basal dendrite length" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 64.86965469270945,
                 "unit" : "μm",
                 "label" : "Total Length",
                 "statistic": "mean",
                 "compartment": "BasalDendrite"
                 }
           ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

    "have the correct basal dendrite Strahler orders" in {
      val query    = queryField(neuronMorphologyId, "morphologyFeature")
      val expected =
        json"""[
                {
                 "value" : 1.4,
                 "unit" : "dimensionless",
                 "label" : "Section Strahler Orders",
                 "statistic": "max",
                 "compartment": "BasalDendrite"
                 }
           ]"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }

   "have the correct basal partition asymmetry index" in {
      val query    = queryField(neuronMorphologyId, "basalDendritePartitionAsymmetry")
      val expected =
        json"""{
               "basalDendritePartitionAsymmetry": {
                 "label" : "Partition Asymmetry",
                 "unit" : "dimensionless",
                 "value" : 0
                 "statistic": "mean",
                 "compartment": "Axon"
                 }
               }
           }"""

      assertOneSource(query) { json =>
        json should be(arrayThatContains(expected))
      }
    }
  }

  /**
    * Defines an ES query that searches for the document with the provided id and limits the resulting source to just
    * the requested field
    */
  private def queryField(id: String, field: String)   =
    jsonContentOf("kg/search/id-query-single-field.json", "id" -> id, "field" -> field)

  private def queryDocument(id: String)               =
    jsonContentOf("kg/search/id-query.json", "id" -> id)

  private def aggregationIn(json: Json): Option[Json] =
    json.hcursor.downField("aggregations").as[Json].toOption

  /** Post a resource across all defined projects in the suite */
  private def postResource(resourcePath: String): IO[List[Assertion]] = {
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
  private def assertOneSource(query: Json)(assertion: Json => Assertion)(implicit pos: Position): IO[Assertion] =
    eventually {
      deltaClient.post[Json]("/search/query", query, Rick) { (body, response) =>
        response.status shouldEqual StatusCodes.OK

        val results = Json
          .fromValues(body.findAllByKey("_source"))
          .hcursor
          .as[List[Json]]
          .getOrElse(Nil)

        results match {
          case single :: Nil => assertion(single)
          case Nil           =>
            fail(
              s"Expected exactly 1 source to match query, got 0.\n " +
                s"Query was ${query.spaces2}"
            )
          case many          =>
            fail(
              s"Expected exactly 1 source to match query, got ${many.size}.\n" +
                s"Query was: ${query.spaces2}\n" +
                s"Results were: $results"
            )
        }
      }
    }

  private def assertEmpty(query: Json)(implicit pos: Position): IO[Assertion] =
    assertOneSource(query)(j => assert(j == json"""{ }"""))

  /** Check that a given field in the json can be parsed as [[Instant]] */
  private def isInstant(json: Json, field: String) =
    json.hcursor.downField(field).as[Instant].isRight

}
